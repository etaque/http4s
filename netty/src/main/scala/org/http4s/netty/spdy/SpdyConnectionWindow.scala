package org.http4s.netty.spdy

import java.util.LinkedList

import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, ChannelFuture, ChannelPromise}
import io.netty.buffer.ByteBuf
import org.http4s.TrailerChunk
import io.netty.handler.codec.spdy.{DefaultSpdyHeadersFrame, DefaultSpdyDataFrame}
import org.http4s.netty.Cancelled
import org.http4s.netty.utils.SpdyConstants

import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 12/5/13
 */
trait SpdyConnectionWindow extends SpdyWindow { self: Logging =>

  private var outboundWindow: Int = initialWindow
  private var queue = new LinkedList[StreamData]()

  def ctx: ChannelHandlerContext


  def getWindow(): Int = outboundWindow

  def writeStreamEnd(streamid: Int, buff: ByteBuf, t: Option[TrailerChunk]): ChannelFuture = queue.synchronized {
    if (buff.readableBytes() >= outboundWindow) {
      val p = ctx.newPromise()
      writeStreamBuffer(streamid, buff).addListener(new ChannelFutureListener {
        def operationComplete(future: ChannelFuture) {
          if (future.isSuccess) {
            if (!t.isEmpty) {
              val msg = new DefaultSpdyHeadersFrame(streamid)
              msg.setLast(true)
              t.get.headers.foreach( h => msg.headers().add(h.name.toString, h.value) )
              ctx.writeAndFlush(msg)
            }
            else {
              val msg = new DefaultSpdyDataFrame(streamid)
              msg.setLast(true)
              ctx.writeAndFlush(msg)
            }
            p.setSuccess()
          }
          else if (future.isCancelled) p.setFailure(new Cancelled(ctx.channel))
          else p.setFailure(future.cause())
        }
      })
      p
    }
    else {
      if (t.isDefined) {
        if (buff.readableBytes() > 0) {
          writeBodyBuff(streamid, buff, false, true) // Don't flush
        }
        val msg = new DefaultSpdyHeadersFrame(streamid)
        msg.setLast(true)
        t.get.headers.foreach( h => msg.headers().add(h.name.toString, h.value) )
        ctx.writeAndFlush(msg)
      }
      else writeBodyBuff(streamid, buff, true, true)
    }
  }

  def writeStreamBuffer(streamid: Int, buff: ByteBuf): ChannelFuture = queue.synchronized {
    logger.trace(s"Writing buffer: ${buff.readableBytes()}, windowsize: $outboundWindow")
    if (buff.readableBytes() > outboundWindow) {
      val p = ctx.newPromise()
      if (outboundWindow > 0) {
        val b = ctx.alloc().buffer(outboundWindow, outboundWindow)
        buff.readBytes(b)
        writeBodyBuff(streamid, b, false, true)
      }
      queue.addLast(StreamData(streamid, buff, p))
      p
    }
    else writeBodyBuff(streamid, buff, false, true)
  }

  def updateWindow(delta: Int) = queue.synchronized {
    logger.trace(s"Updating window, delta: $delta")
    outboundWindow += delta
    if (!queue.isEmpty && outboundWindow > 0) {   // Send more chunks
      val next = queue.poll()
      if (next.buff.readableBytes() > outboundWindow) { // Can only send part
        val b = ctx.alloc().buffer(outboundWindow, outboundWindow)
        next.buff.readBytes(b)
        writeBodyBuff(next.streamid, b, false, true)
        queue.addFirst(next)  // prepend to the queue
      }
      else {   // write the whole thing and get another chunk
        writeBodyBuff(next.streamid, next.buff, false, queue.isEmpty || outboundWindow >= 0)
        next.p.setSuccess()
        updateWindow(0)
      }
    }
  }

  // Should only be called from inside the synchronized methods
  private def writeBodyBuff(streamid: Int, buff: ByteBuf, islast: Boolean, flush: Boolean): ChannelFuture = {
    outboundWindow -= buff.readableBytes()

    // Don't exceed maximum frame size
    while(buff.readableBytes() > SpdyConstants.SPDY_MAX_LENGTH) {
      val b = ctx.alloc().buffer(SpdyConstants.SPDY_MAX_LENGTH, SpdyConstants.SPDY_MAX_LENGTH)
      val msg = new DefaultSpdyDataFrame(streamid, b)
      ctx.write(msg)
    }

    val msg = new DefaultSpdyDataFrame(streamid, buff)
    msg.setLast(islast)
    if (flush) ctx.writeAndFlush(msg)
    else ctx.write(msg)
  }

  private case class StreamData(streamid: Int, buff: ByteBuf, p: ChannelPromise)
}
