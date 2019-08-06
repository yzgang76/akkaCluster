package com.hpe.ossm.scluster.management

import akka.actor.{Actor, ActorLogging, DeadLetter}
import com.hpe.ossm.jcluster.messages.{LookingForService, ServiceStatusEvents}

class DeadLetterListener extends Actor with ActorLogging{
    override def receive:Receive={
//        case d:DeadLetter =>
//            d.sender ! Resend(d.message.asInstanceOf[MsgData],d.recipient)
//            val clone=d.message.asInstanceOf[MsgData]
//            val n=clone.getNomOfResend
//            if(n<2){
//                log.warning(s"RESENDING .... ${clone.getKey}, try number= ${n+1}")
//                clone.setNomOfResend(n+1)
//                d.recipient.!(clone)(d.sender)
////                d.sender ! clone
//            }
        case d:DeadLetter if d.message.isInstanceOf[ServiceStatusEvents] ||d.message.isInstanceOf[LookingForService] =>
                log.error(s"Dead letter: from ${d.sender} to ${d.recipient} with ${d.message.toString}")
        case _=>
    }
}
