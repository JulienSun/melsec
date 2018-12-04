package com.vsdata.melsec.client;

import com.vsdata.melsec.MelsecTimeoutException;
import com.vsdata.melsec.message.e.FrameECommand;
import com.vsdata.melsec.message.e.FrameEResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author liumin
 */
public abstract class AbstractTcpClient implements MelsecTcpClient {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ConcurrentLinkedQueue<PendingRequest<? extends FrameEResponse>> pendingRequests = new ConcurrentLinkedQueue<>();

    private final ChannelManager channelManager;

    protected final MelsecClientConfig config;

    public AbstractTcpClient(MelsecClientConfig config) {
        this.config = config;
        channelManager = new ChannelManager(this);
    }

    @Override
    public CompletableFuture<Channel> bootstrap() {
        CompletableFuture<Channel> future = new CompletableFuture<>();
        Bootstrap bootstrap = new Bootstrap();
        config.getBootstrapConsumer().accept(bootstrap);
        bootstrap.group(config.getEventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getTimeout().toMillis())
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    AbstractTcpClient.this.initChannel(ch.pipeline());
                    ch.pipeline().addLast(new MelsecClientHandler(AbstractTcpClient.this));
                }
            })
            .connect(config.getAddress(), config.getPort())
            .addListener((ChannelFuture f) -> {
                if (f.isSuccess()) {
                    future.complete(f.channel());
                } else {
                    future.completeExceptionally(f.cause());
                }
            });

        return future;
    }

    /**
     * 初始化Channel，增加相应的编解码器
     *
     * @param pipeline ChannelPipeline
     * @throws Exception 异常
     */
    protected abstract void initChannel(ChannelPipeline pipeline) throws Exception;

    private static class MelsecClientHandler extends SimpleChannelInboundHandler<FrameEResponse> {

        private MelsecTcpClient client;

        private MelsecClientHandler(MelsecTcpClient client) {
            this.client = client;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext channelHandlerContext, FrameEResponse response) throws Exception {
            client.onChannelRead(channelHandlerContext, response);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            client.exceptionCaught(ctx, cause);
        }
    }

    @Override
    public CompletableFuture<MelsecTcpClient> connect() {
        CompletableFuture<MelsecTcpClient> future = new CompletableFuture<>();

        channelManager.getChannel().whenComplete((ch, ex) -> {
            if (ch != null) {
                future.complete(AbstractTcpClient.this);
            } else {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<MelsecTcpClient> disconnect() {
        return channelManager.disconnect().thenApply(v -> this);
    }

    @Override
    public <T extends FrameEResponse> CompletableFuture<T> sendRequest(FrameECommand request) {
        CompletableFuture<T> future = new CompletableFuture<>();

        channelManager.getChannel().whenComplete((ch, ex) -> {
            if (ch != null) {
                Timeout timeout = config.getWheelTimer().newTimeout(t -> {
                    if (t.isCancelled()) {
                        return;
                    }
                    PendingRequest<? extends FrameEResponse> timedOut = pendingRequests.poll();
                    if (timedOut != null) {
                        timedOut.promise.completeExceptionally(new MelsecTimeoutException(config.getTimeout()));
                    }
                }, config.getTimeout().getSeconds(), TimeUnit.SECONDS);

                pendingRequests.add(new PendingRequest<>(future, timeout));
                ch.writeAndFlush(request).addListener(f -> {
                    if (!f.isSuccess()) {
                        PendingRequest<?> p = pendingRequests.poll();
                        if (p != null) {
                            p.promise.completeExceptionally(f.cause());
                            p.timeout.cancel();
                        }
                    }
                });
            } else {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    @Override
    public void onChannelRead(ChannelHandlerContext ctx, FrameEResponse response) {
        config.getExecutor().submit(() -> handleResponse(response));
    }

    private void handleResponse(FrameEResponse response) {
        PendingRequest<?> pending = pendingRequests.poll();
        if (pending != null) {
            pending.timeout.cancel();
            pending.promise.complete(response);
        } else {
            ReferenceCountUtil.release(response);
            logger.debug("Received response for unknown response: {}", response);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        failPendingRequests(cause);
        ctx.close();
        onExceptionCaught(ctx, cause);
    }

    /**
     * Logs the exception on DEBUG level.
     * <p>
     * Subclasses may override to customize logging behavior.
     *
     * @param ctx   the {@link ChannelHandlerContext}.
     * @param cause the exception that was caught.
     */
    @SuppressWarnings("WeakerAccess")
    protected void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.debug("Exception caught: {}", cause.getMessage(), cause);
    }

    private void failPendingRequests(Throwable cause) {
        pendingRequests.forEach(p -> p.promise.completeExceptionally(cause));
        pendingRequests.clear();
    }

    private static class PendingRequest<T> {
        private final CompletableFuture<FrameEResponse> promise = new CompletableFuture<>();

        private final Timeout timeout;

        @SuppressWarnings("unchecked")
        private PendingRequest(CompletableFuture<T> future, Timeout timeout) {
            this.timeout = timeout;

            promise.whenComplete((r, ex) -> {
                if (r != null) {
                    try {
                        future.complete((T) r);
                    } catch (ClassCastException e) {
                        future.completeExceptionally(e);
                    }
                } else {
                    future.completeExceptionally(ex);
                }
            });
        }
    }
}