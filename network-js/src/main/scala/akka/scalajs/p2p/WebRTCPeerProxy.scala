package akka.scalajs.p2p

import scala.scalajs.js

import akka.actor._
import akka.scalajs.jsapi._
import akka.scalajs.common._

import org.scalajs.spickling._
import org.scalajs.spickling.jsany._

private abstract class WebRTCPeerProxy(handlerProps: ActorRef => Props) extends Actor {
  RegisterWebRTCPicklers.registerPicklers()
  var peerConnection: webkitRTCPeerConnection = _
  var dataChannel: Option[RTCDataChannel] = None
  var handlerActor: ActorRef = _

  override def preStart(): Unit = {
    peerConnection = new webkitRTCPeerConnection(null, DataChannelsConstraint)
  }
  
  override def postStop(): Unit = {
    dataChannel.foreach(_.close())
    peerConnection.close()
  }

  override def receive = {
    case SignalingChannel(peer: ActorRef) =>
      peerConnection.onicecandidate = { event: RTCIceCandidateEvent =>
        if(event.candidate != null) {
          peer ! IceCandidate(js.JSON.stringify(event.candidate))
        }
      }
      receivedSignalingChannel(peer)
    case SessionDescription(description) =>
      receivedSessionDescription(
        new RTCSessionDescription(js.JSON.parse(description).asInstanceOf[RTCSessionDescriptionInit])
      )
    case IceCandidate(candidate) =>
      peerConnection.addIceCandidate(new RTCIceCandidate(js.JSON.parse(candidate).asInstanceOf[RTCIceCandidate]))
    case Terminated(a) if a == handlerActor =>
      context.stop(self)
    case message =>
      val pickle = PicklerRegistry.pickle(message)
      dataChannel.foreach(_.send(js.JSON.stringify(pickle)))
  }
  
  def setDataChannel(dc: RTCDataChannel) = {
    dc.onopen = { event: Event =>
      handlerActor = context.watch(context.actorOf(handlerProps(self)))
    }
    dc.onmessage = { event: RTCMessageEvent =>
      val pickle = js.JSON.parse(event.data.toString())
      val message = PicklerRegistry.unpickle(pickle.asInstanceOf[js.Any])
      handlerActor ! message
    }
    dc.onclose = { event: Event =>
      handlerActor ! PoisonPill
    }
    dc.onerror = { event: Event =>
      handlerActor ! PoisonPill
    }
    dataChannel = Some(dc)
  }

  def receivedSignalingChannel(peer: ActorRef): Unit

  def receivedSessionDescription(description: RTCSessionDescription): Unit
}

private object OptionalMediaConstraint extends RTCOptionalMediaConstraint {
  override val DtlsSrtpKeyAgreement: js.Boolean = false
  override val RtpDataChannels: js.Boolean = true
}
private object DataChannelsConstraint extends RTCMediaConstraints {
  override val mandatory: RTCMediaOfferConstraints = null
  override val optional: js.Array[RTCOptionalMediaConstraint] = js.Array(OptionalMediaConstraint)
}
