package akka.amqp
import scala.concurrent.Future
import akka.actor.FSM.{ CurrentState, Transition }
import scala.concurrent.{ ExecutionContext, Promise }
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import java.lang.Thread
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ThreadFactory, Executors }
import akka.actor._
import akka.util.Timeout
import akka.pattern.ask
import java.io.IOException

sealed trait ConnectionState
case object Disconnected extends ConnectionState
case object Connected extends ConnectionState

sealed trait ConnectionMessage
case object Connect extends ConnectionMessage
case object Disconnect extends ConnectionMessage
case class ConnectionCallback[T](callback: RabbitConnection ⇒ T) extends ConnectionMessage

private class ReconnectTimeoutGenerator {

  var currentTimeout = 1;
  var previousTimeout = 1
  def nextTimeoutSec(maxTimeoutSec: Int): Int = {
    if (currentTimeout < maxTimeoutSec) {
      val result = currentTimeout
      val nextTimeout = currentTimeout + previousTimeout
      currentTimeout = nextTimeout
      previousTimeout = result
      result
    } else {
      maxTimeoutSec
    }
  }

  def reset() {
    currentTimeout = 1
    previousTimeout = 1
  }
}
private[akka] case class CreateRandomNameChild(props: Props)
private[amqp] class DurableConnectionActor(settings: AmqpSettings)
  extends Actor with FSM[ConnectionState, Option[RabbitConnection]] with ShutdownListener {

  import settings._
  val connectionFactory = new ConnectionFactory()
  connectionFactory.setRequestedHeartbeat(amqpHeartbeat.toSeconds.toInt)
  connectionFactory.setUsername(user)
  connectionFactory.setPassword(pass)
  connectionFactory.setVirtualHost(vhost)

  lazy val timeoutGenerator = new ReconnectTimeoutGenerator

  val executorService = Executors.newFixedThreadPool(channelThreads, new ThreadFactory {
    import connectionFactory._
    val uri = "amqp://%s@%s:%s%s".format(getUsername, getHost, getPort, getVirtualHost)
    val counter = new AtomicInteger()
    def newThread(r: Runnable) = {
      val t = new Thread(r)
      t.setDaemon(true)
      t.setName("%s:channel-%s".format(uri, counter.incrementAndGet()))
      t
    }
  })

  startWith(Disconnected, None)

  when(Disconnected) {
    case Event(Connect, _) ⇒
      log.info("Connecting to one of [{}]", addresses.mkString(", "))
      try {
        val connection = connectionFactory.newConnection(executorService, addresses.map(RabbitAddress.parseAddress).toArray)
        connection.addShutdownListener(this)
        log.info("Successfully connected to {}", connection)
        cancelTimer("reconnect")
        timeoutGenerator.reset()
        goto(Connected) using Some(connection)
      } catch {
        case e: Exception ⇒
          log.error(e, "Error while trying to connect")
          val nextReconnectTimeout = timeoutGenerator.nextTimeoutSec(maxReconnectDelay.toSeconds.toInt)
          setTimer("reconnect", Connect, nextReconnectTimeout seconds, true)
          log.info("Reconnecting in {} seconds...".format(nextReconnectTimeout))
          stay()
      }
    case Event(Disconnect, _) ⇒
      cancelTimer("reconnect")
      log.info("Already disconnected")
      stay()
    case Event(cause: ShutdownSignalException, _) ⇒
     stay()
  }

  when(Connected) {
    case Event(ConnectionCallback(callback), Some(connection)) ⇒
      stay() replying callback.apply(connection)
    case Event(Disconnect, Some(connection)) ⇒
      try {
        log.debug("Disconnecting from {}", connection)
        connection.close()
        log.info("Successfully disconnected from {}", connection)
      } catch {
        case e: Exception ⇒ log.error(e, "Error while closing connection")
      }
      goto(Disconnected)
    case Event(cause: ShutdownSignalException, Some(connection)) ⇒
      if (cause.isHardError) {
        log.error(cause, "Connection broke down {}", connection)
        self ! Connect
      }
      goto(Disconnected)
  }
  whenUnhandled {
    case Event(CreateRandomNameChild(child), _) ⇒
      sender ! (try context.actorOf(child) catch { case e: Exception ⇒ e })
      stay()
  }

  initialize

  onTermination {
    case StopEvent(reason, state, stateData) ⇒
      stateData foreach (_.close())
      log.debug("Successfully disposed")
      executorService.shutdown()
  }

  def shutdownCompleted(cause: ShutdownSignalException) {
    self ! cause
  }
}

private[amqp] object ConnectionConnected {
  def unapply(msg: Any) = msg match {
    case Transition(connection, _, Connected) ⇒ Some(connection)
    case CurrentState(connection, Connected)  ⇒ Some(connection)
    case _                                    ⇒ None
  }
}

private[amqp] object ConnectionDisconnected {
  def unapply(msg: Any) = msg match {
    case Transition(_, _, Disconnected) ⇒ true
    case CurrentState(_, Disconnected)  ⇒ true
    case _                              ⇒ false
  }
}

trait ChannelBuilders {
  implicit val extension : AmqpExtensionImpl
  protected val connection : DurableConnection
  lazy val system : ActorSystem = extension._system
  
  def newChannel(persistent: Boolean = false) = new connection.DurableChannel {
    override val persistentChannel = persistent
  }
  
  def newChannelForPublisher(persistent: Boolean = false) = new connection.DurableChannel with CanBuildDurablePublisher {
   override val persistentChannel = persistent
  }
  
  /**
   * persistence is always false on the ConfirmingPublisher
   */
  def newChannelForConfirmingPublisher = new connection.DurableChannel with CanBuildConfirmingPublisher
  
  def newChannelForConsumer(persistent: Boolean = false) = new connection.DurableChannel with CanBuildDurableConsumer {
   override val persistentChannel = persistent
  }

  
}

class DurableConnection private[amqp] (implicit val extension: AmqpExtensionImpl) extends HasDurableChannel with ChannelBuilders {
  
override protected val connection = this
import extension._
  private[amqp] val durableConnectionActor = system.actorOf(Props(new DurableConnectionActor(settings)), "amqp-connection")
  durableConnectionActor ! Connect
  

  def withConnection[T : reflect.ClassTag](callback: RabbitConnection ⇒ T): Future[T] = {
    import ExecutionContext.Implicits.global
    implicit val timeout = Timeout(settings.interactionTimeout)
    (durableConnectionActor ? ConnectionCallback(callback)).mapTo[T]
  }

  def withTempChannel[T : reflect.ClassTag](callback: RabbitChannel ⇒ T): Future[T] = {
    withConnection { conn ⇒
      val ch = conn.createChannel()
      try {
        callback(ch)
      } finally {
        if (ch.isOpen) { ch.close() }
      }
    }
  }


  /**
   * have the actor disconnect
   */
  def disconnect() {
    durableConnectionActor ! Disconnect
  }

  /**
   * tell the Actor to connect
   */
  def connect() {
    durableConnectionActor ! Connect
  }

  /**
   * kill the connection and the actor handling that connection
   */
  def dispose() {
    if (!durableConnectionActor.isTerminated) {
      durableConnectionActor ! Disconnect
      durableConnectionActor ! PoisonPill
    }
  }
}