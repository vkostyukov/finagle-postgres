package com.twitter.finagle.postgres

import com.twitter.finagle.ServiceFactory
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import protocol._
import com.twitter.util.Future
import java.util.concurrent.atomic.AtomicLong
import protocol.Describe
import protocol.Field
import protocol.CommandCompleteResponse
import protocol.Parse
import protocol.PgRequest
import protocol.Query
import protocol.SelectResult
import protocol.RowDescriptions
import protocol.Rows
import protocol.Value
import org.jboss.netty.buffer.ChannelBuffer
import scala.util.Random
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import com.twitter.logging.Logger


class Client(factory: ServiceFactory[PgRequest, PgResponse], id:String) {
  private[this] val counter = new AtomicLong(0)
  private[this] lazy val customTypes = CustomOIDProxy.serviceOIDMap(id)
  private[this] val pool = new ConcurrentHashMap[String, Service[PgRequest, PgResponse]]().asScala
  private[this] val logger = Logger("client")

  def query(sql: String): Future[QueryResponse] = sendQuery(sql) {
    case SelectResult(fields, rows) => Future(ResultSet(fields, rows, customTypes))
    case CommandCompleteResponse(affected) => Future(OK(affected))
  }

  def fetch(sql: String): Future[SelectResult] = sendQuery(sql) {
    case rs: SelectResult => Future(rs)
  }

  def executeUpdate(sql: String): Future[OK] = sendQuery(sql) {
    case CommandCompleteResponse(rows) => Future(OK(rows))
  }

  def select[T](sql: String)(f: Row => T): Future[Seq[T]] = fetch(sql) map { rs =>
    extractRows(rs).map(f)
  }

  def prepare(sql: String): Future[PreparedStatement] = for {
    name <- parse(sql)
  } yield new PreparedStatementImpl(name)

  def close() = factory.close()

  private[this] def parse(sql: String): Future[String] = {
    val name = genName()
    send(PgRequest(Parse(name, sql), flush = true)) {
      case ParseCompletedResponse => Future.value(name)
    }
  }

  private[this] def bind(name: String, params: Seq[ChannelBuffer] = Seq()): Future[Unit] =
    send(PgRequest(Bind(portal = name, name = name, params = params), flush = true)) {
      case BindCompletedResponse => Future.value(())
    }

  private[this] def describe(name: String): Future[(IndexedSeq[String], IndexedSeq[ChannelBuffer => Value[Any]])] = send(PgRequest(Describe(portal = true, name = name), flush = true)) {
    case RowDescriptions(fields) => Future.value(processFields(fields))
  }

  private[this] def execute(name: String, maxRows: Int = 0) = fire(PgRequest(Execute(name, maxRows), flush = true))

  private[this] def sendQuery[T](sql: String)(handler: PartialFunction[PgResponse, Future[T]]) = send(PgRequest(new Query(sql)))(handler)

  private[this] def close(service: Service[PgRequest, PgResponse]) = for {
    _ <- service(PgRequest(Sync))
    _ <- service(PgRequest(new Query("DISCARD ALL;")))
  } yield service.close()

  private[this] def fire(req: PgRequest) = req.msg match {
    case Parse(name, _, _) => fireAndRemember(name, req)
    case Bind(_, name, _, _, _) => fireToRemembered(name, req)
    case Describe(_, name) => fireToRemembered(name, req)
    case Execute(name, _) => fireAndRemove(name, req)
    case _ => fireAndClose(req)
  }

  private[this] def fireAndRemember(name: String, req: PgRequest) = {
    logger.ifDebug {
      s"creating a new service for $name handling the $req"
    }

    factory() flatMap { service =>
      service(req) onSuccess { _ =>
        pool.putIfAbsent(name, service)
      }
    }
  }

  private[this] def fireAndRemove(name: String, req: PgRequest) = {
    val service = pool(name)

    logger.ifDebug {
      s"removing service $name handling the $req"
    }

    service(req) ensure {
      pool.remove(name, service)
      close(service)
    }
  }

  private[this] def fireToRemembered(name: String, req: PgRequest) = {
    logger.ifDebug {
      s"firing to a remembered service $name handling the $req"
    }

    val service = pool(name)
    service(req) onFailure { _ =>
      pool.remove(name, service)
      close(service)
    }
  }

  private[this] def fireAndClose(req: PgRequest) = {
    logger.ifDebug {
      "a regular behavior: fire & close handling the $req"
    }

    factory() flatMap { service =>
      service(req) ensure {
        close(service)
      }
    }
  }

  private[this] def send[T](r: PgRequest)(handler: PartialFunction[PgResponse, Future[T]]) = fire(r) flatMap (handler orElse {
    case some => throw new UnsupportedOperationException("TODO Support exceptions correctly " + some)
  })

  private[this] def processFields(fields: IndexedSeq[Field]): (IndexedSeq[String], IndexedSeq[ChannelBuffer => Value[Any]]) = {
    val names = fields.map(f => f.name)
    val parsers = fields.map(f => ValueParser.parserOf(f.format, f.dataType, customTypes))

    (names, parsers)
  }

  private[this] def extractRows(rs: SelectResult): List[Row] = {
    val (fieldNames, fieldParsers) = processFields(rs.fields)

    rs.rows.map(dataRow => new Row(fieldNames, dataRow.data.zip(fieldParsers).map {
      case (d, p) => if (d == null) null else p(d)
    }))
  }

  private[this] class PreparedStatementImpl(name: String) extends PreparedStatement {
    def fire(params: Any*): Future[QueryResponse] = {
      val binaryParams = params.map(p => StringValueEncoder.encode(p))
      for {
        _ <- bind(name, binaryParams)
        (fieldNames, fieldParsers) <- describe(name)
        exec <- execute(name)
      } yield exec match {
        case CommandCompleteResponse(rows) => OK(rows)
        case Rows(rows, true) => ResultSet(fieldNames, fieldParsers, rows, customTypes)
      }
    }
  }

  private[this] def genName() = "fin-pg-" + counter.incrementAndGet
}

object Client {

  def apply(host: String, username: String, password: Option[String], database: String, connectionLimit: Int = 10): Client = {
    val id = Random.alphanumeric.take(28).mkString

    val factory: ServiceFactory[PgRequest, PgResponse] = ClientBuilder()
      .codec(new PgCodec(username, password, database, id))
      .hosts(host)
      .hostConnectionLimit(connectionLimit)
      .buildFactory()

    new Client(factory, id)
  }
}

class Row(val fields: IndexedSeq[String], val vals: IndexedSeq[Value[Any]]) {

  private[this] val indexMap = fields.zipWithIndex.toMap

  def getOption[A](name: String): Option[A] = indexMap.get(name).map(vals(_)) match {
    case Some(Value(x)) => Some(x.asInstanceOf[A])
    case _ => None
  }

  def get[A](name: String)(implicit mf: Manifest[A]): A = getOption[A](name) match {
    case Some(x) => x
    case _ => throw new IllegalStateException("Expected type " + mf.toString)
  }

  def getOrElse[A](name: String, default: => A)(implicit mf:Manifest[A]):A = {
    getOption[A](name) match {
      case Some(x) => x
      case _ => default
    }
  }

  def get(index: Int): Value[Any] = vals(index)

  def values(): IndexedSeq[Value[Any]] = vals

  override def toString = "{ fields='" + fields.toString + "', rows='" + vals.toString + "'}"
}

sealed trait QueryResponse

case class OK(affectedRows: Int) extends QueryResponse

case class ResultSet(rows: List[Row]) extends QueryResponse

object ResultSet {

  // TODO copy-paste
  def apply(fieldNames: IndexedSeq[String], fieldParsers: IndexedSeq[ChannelBuffer => Value[Any]], rows: List[DataRow], customTypes:Map[String, String]) = new ResultSet(rows.map(dataRow => new Row(fieldNames, dataRow.data.zip(fieldParsers).map({
    case (d, p) => if (d == null) null else p(d)
  }))))

  def apply(fields: IndexedSeq[Field], rows: List[DataRow], customTypes:Map[String, String]): ResultSet = {
    val (fieldNames, fieldParsers) = processFields(fields, customTypes)

    apply(fieldNames, fieldParsers, rows, customTypes)
  }

  private[this] def processFields(fields: IndexedSeq[Field], customTypes:Map[String, String]): (IndexedSeq[String], IndexedSeq[ChannelBuffer => Value[Any]]) = {
    val names = fields.map(f => f.name)
    val parsers = fields.map(f => ValueParser.parserOf(f.format, f.dataType, customTypes))

    (names, parsers)
  }

}

trait PreparedStatement {
  def fire(params: Any*): Future[QueryResponse]

  def exec(params: Any*): Future[OK] = fire(params: _*) map {
    case ok: OK => ok
    case ResultSet(_) => throw Errors.client("Update query expected")
  }

  def select[T](params: Any*)(f: Row => T): Future[Seq[T]] = fire(params: _*) map {
    case ResultSet(rows) => rows.map(f)
    case OK(_) => throw Errors.client("Select query expected")
  }

}
