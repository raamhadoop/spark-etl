package spark_etl.model

import net.jcazevedo.moultingyaml._
import org.apache.spark.sql.catalyst.parser._
import spark_etl.parser.Parser
import spark_etl.util._
import spark_etl.{ConfigError, ExtractReader, LoadWriter}

import scala.util.{Failure, Success, Try}
import scalaz.Scalaz._
import scalaz.Validation.FlatMap._
import scalaz._

case class RuntimeExtract(org: Extract, checkContents: Option[String])

case class RuntimeTransform(org: Transform, sqlContents: String, checkContents: Option[String])

class RuntimeContext(extracts: List[RuntimeExtract], transforms: List[RuntimeTransform], val loads: List[Load], val extractReader: ExtractReader, val loadWriter: LoadWriter, depTree: DepTree, conf: Config) {
  def allExtracts: List[RuntimeExtract] =
    (for {
      r <- depTree.forType(E)
      e <- extracts
      if r.id == e.org.name
    } yield e).toList

  def allTransforms: List[RuntimeTransform] =
    (for {
      r <- depTree.forType(T)
      t <- transforms
      if r.id == t.org.name
    } yield t).toList

  def allLoads: List[Load] =
    (for {
      r <- depTree.forType(L)
      l <- loads
      if r.id == l.name
    } yield l).toList

  def asDot = depTree.asDot()
}

object RuntimeContext extends DefaultYamlProtocol {

  /**
    * Emphasis on *maximum* validation.
    */
  def load(_conf: Config, filePathRoot: String, env: Map[String, String]): ValidationNel[ConfigError, RuntimeContext] = {
    // depTree, with the known universe
    val conf = toLowerCase(_conf)
    val depTree = new DepTree(
      conf.extracts.map(e => Vertex(e.name, E)) ++
        conf.transforms.map(t => Vertex(t.name, T)) ++
        conf.loads.map(l => Vertex(l.name, L))
    )

    // read in entities and add their deps
    val regExtracts = conf.extracts
      .map(e => registerExtractDeps(e, depTree, filePathRoot, env))
      .map(_.map(List(_))).reduce(_ +++ _)

    val regTransforms = conf.transforms
      .map(t => registerTransformDeps(t, depTree, filePathRoot, env))
      .map(_.map(List(_))).reduce(_ +++ _)

    conf.loads.foreach(l => depTree.addEdge(l.source, Vertex(l.name, L)))

    val validatedDuplicates = validateDuplicates(conf)

    val validatedDepTree = validateDepTree(depTree)

    val validatedExtractor = instantiate[ExtractReader](conf.extract_reader.get, classOf[ExtractReader])

    val validatedTransformer = instantiate[LoadWriter](conf.load_writer.get, classOf[LoadWriter])

    (validatedDuplicates |@| regExtracts |@| regTransforms |@| validatedExtractor |@| validatedTransformer |@| validatedDepTree) { (dups, es, ts, e, t, dt) => new RuntimeContext(es, ts, conf.loads, e, t, depTree, conf)  }
  }

  private def toLowerCase(conf: Config): Config =
    conf.copy(
      extracts = conf.extracts.map(e => e.copy(name = e.name.toLowerCase)),
      transforms = conf.transforms.map(t => t.copy(name = t.name.toLowerCase)),
      loads = conf.loads.map(l => l.copy(source = l.source.toLowerCase))
    )

  private def validateDuplicates(conf: Config): ValidationNel[ConfigError, Unit] = {
    def valDups(desc: String, candidates: Seq[String]): ValidationNel[ConfigError, Unit] =
      (candidates diff candidates.distinct).distinct match {
        case Nil   => ().successNel[ConfigError]
        case other => ConfigError(s"Duplicates found for $desc: ${other.sorted.mkString(", ")}").failureNel[Unit]
      }

    (valDups("extract names",   conf.extracts.map(_.name))                                             |@|
     valDups("extract uris",    conf.extracts.map(_.uri))                                              |@|
     valDups("extract check",   conf.extracts.collect { case e if e.check.isDefined => e.check.get })  |@|
     valDups("transform names", conf.transforms.map(_.name))                                           |@|
     valDups("transform sqls",  conf.transforms.map(_.sql))                                            |@|
     valDups("transform check", conf.extracts.collect { case t if t.check.isDefined => t.check.get })  |@|
     valDups("load names",      conf.loads.map(_.name))                                                |@|
     valDups("load uris",       conf.loads.map(_.uri))) { (_, _, _, _, _, _, _ ,_ ) => ()}
  }

  /**
    * Load & parse check, if specified
    * Note, extract check is only dependant on the extract
    */
  private def registerExtractDeps(extract: Extract, depTree: DepTree, filePathRoot: String, env: Map[String, String]): ValidationNel[ConfigError, RuntimeExtract] =
    extract.check match {
      case Some(checkUri) =>
        UriLoader.load(checkUri, filePathRoot, env)
          .flatMap(validateResolvedDsos(depTree, extract.name, Echeck, s"extract check ${extract.name} (uri $checkUri)"))
          .map(checkTxt => RuntimeExtract(extract, Some(checkTxt)))
      case None =>
        RuntimeExtract(extract, None).successNel[ConfigError]
    }

  /**
    * Load & parse sql
    * Load & parse pre_check, if specified
    * Load & parse post_check, if specified
    * Check dso dependencies
    */
  private def registerTransformDeps(transform: Transform, depTree: DepTree, filePathRoot: String, env: Map[String, String]): ValidationNel[ConfigError, RuntimeTransform] = {
    // load resources
    val validatedSql = UriLoader.load(transform.sql, filePathRoot, env)
      .flatMap(validateResolvedDsos(depTree, transform.name, T, s"Unparsable sql of transform ${transform.name}"))
    val validatedCheck = liftOpt(transform.check)(r => UriLoader.load(r, filePathRoot, env)
      .flatMap(validateResolvedDsos(depTree, transform.name, Tcheck, s"Unparsable sql of transform check ${transform.name}")))

    (validatedSql |@| validatedCheck) { (sql, check) => RuntimeTransform(transform, sql, check) }
  }

  private def liftOpt[T1, T2](opt: Option[T1])(toVal: T1 => ValidationNel[ConfigError, T2]): ValidationNel[ConfigError, Option[T2]] =
    opt match {
      case Some(r) => toVal(r).map(Some(_))
      case None => None.successNel[ConfigError]
    }

  private def validateResolvedDsos(depTree: DepTree, name: String, `type`: VertexType, errMsgPrefix: String)(contents: String): ValidationNel[ConfigError, String] =
    Try(Parser.getDsos(contents)) match {
      case Success(usedDsos) =>
        usedDsos.map(_.toLowerCase).foreach(d => depTree.addEdge(d, Vertex(name, `type`)))
        contents.successNel[ConfigError]
      case Failure(e: ParseException) =>
        ConfigError(s"$errMsgPrefix: failed to parse, error: ${e.getMessage}").failureNel[String]
      case Failure(e) =>
        ConfigError(s"$errMsgPrefix: failed to parse", Some(e)).failureNel[String]
    }

  private def validateDepTree(depTree: DepTree): ValidationNel[ConfigError, Unit] = {
    val danglingDeps = depTree.dangling
    if (danglingDeps.isEmpty) {
      ().successNel[ConfigError]
    } else {
      val errors = for {
        dangling <- danglingDeps
      } yield ConfigError(s"Unresolved dependency ${dangling.source.id} for ${dangling.target.`type`.asStr} ${dangling.target.id}").failureNel[Unit]
      errors.reduce(_ +++ _)
    }
  }

  private def instantiate[T](paramConstr: ParametrizedConstructor, parentClass: Class[_]): ValidationNel[ConfigError, T] = {
    Try {
      val clazz = Class.forName(paramConstr.`class`)
      if (parentClass.isAssignableFrom(clazz)) {
        val constructor = clazz.getConstructors()(0)
        constructor.newInstance(paramConstr.params.get).asInstanceOf[T].successNel[ConfigError]
      } else {
        ConfigError(s"Failed to cast class ${paramConstr.`class`} to ${parentClass.getName}").failureNel[T]
      }
    } match {
      case scala.util.Success(validated) => validated
      case scala.util.Failure(e) => ConfigError(s"Failed to instantiate class ${paramConstr.`class`} with params: ${paramConstr.params}", Some(e)).failureNel[T]
    }
  }
}
