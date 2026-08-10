package org.nexus.p2p;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 */
@javax.annotation.processing.Generated(
    value = "by gRPC proto compiler (version 1.22.1)",
    comments = "Source: nexus.proto")
public final class NexusChainGrpc {

  private NexusChainGrpc() {}

  public static final String SERVICE_NAME = "NexusChain";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<org.nexus.p2p.NexusChainOuterClass.Message,
      org.nexus.p2p.NexusChainOuterClass.Message> getEntryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Entry",
      requestType = org.nexus.p2p.NexusChainOuterClass.Message.class,
      responseType = org.nexus.p2p.NexusChainOuterClass.Message.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<org.nexus.p2p.NexusChainOuterClass.Message,
      org.nexus.p2p.NexusChainOuterClass.Message> getEntryMethod() {
    io.grpc.MethodDescriptor<org.nexus.p2p.NexusChainOuterClass.Message, org.nexus.p2p.NexusChainOuterClass.Message> getEntryMethod;
    if ((getEntryMethod = NexusChainGrpc.getEntryMethod) == null) {
      synchronized (NexusChainGrpc.class) {
        if ((getEntryMethod = NexusChainGrpc.getEntryMethod) == null) {
          NexusChainGrpc.getEntryMethod = getEntryMethod = 
              io.grpc.MethodDescriptor.<org.nexus.p2p.NexusChainOuterClass.Message, org.nexus.p2p.NexusChainOuterClass.Message>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "NexusChain", "Entry"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.nexus.p2p.NexusChainOuterClass.Message.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.nexus.p2p.NexusChainOuterClass.Message.getDefaultInstance()))
                  .setSchemaDescriptor(new NexusChainMethodDescriptorSupplier("Entry"))
                  .build();
          }
        }
     }
     return getEntryMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static NexusChainStub newStub(io.grpc.Channel channel) {
    return new NexusChainStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static NexusChainBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new NexusChainBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static NexusChainFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new NexusChainFutureStub(channel);
  }

  /**
   */
  public static abstract class NexusChainImplBase implements io.grpc.BindableService {

    /**
     */
    public void entry(org.nexus.p2p.NexusChainOuterClass.Message request,
        io.grpc.stub.StreamObserver<org.nexus.p2p.NexusChainOuterClass.Message> responseObserver) {
      asyncUnimplementedUnaryCall(getEntryMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getEntryMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                org.nexus.p2p.NexusChainOuterClass.Message,
                org.nexus.p2p.NexusChainOuterClass.Message>(
                  this, METHODID_ENTRY)))
          .build();
    }
  }

  /**
   */
  public static final class NexusChainStub extends io.grpc.stub.AbstractStub<NexusChainStub> {
    private NexusChainStub(io.grpc.Channel channel) {
      super(channel);
    }

    private NexusChainStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NexusChainStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new NexusChainStub(channel, callOptions);
    }

    /**
     */
    public void entry(org.nexus.p2p.NexusChainOuterClass.Message request,
        io.grpc.stub.StreamObserver<org.nexus.p2p.NexusChainOuterClass.Message> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getEntryMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class NexusChainBlockingStub extends io.grpc.stub.AbstractStub<NexusChainBlockingStub> {
    private NexusChainBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private NexusChainBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NexusChainBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new NexusChainBlockingStub(channel, callOptions);
    }

    /**
     */
    public org.nexus.p2p.NexusChainOuterClass.Message entry(org.nexus.p2p.NexusChainOuterClass.Message request) {
      return blockingUnaryCall(
          getChannel(), getEntryMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class NexusChainFutureStub extends io.grpc.stub.AbstractStub<NexusChainFutureStub> {
    private NexusChainFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private NexusChainFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NexusChainFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new NexusChainFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<org.nexus.p2p.NexusChainOuterClass.Message> entry(
        org.nexus.p2p.NexusChainOuterClass.Message request) {
      return futureUnaryCall(
          getChannel().newCall(getEntryMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_ENTRY = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final NexusChainImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(NexusChainImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_ENTRY:
          serviceImpl.entry((org.nexus.p2p.NexusChainOuterClass.Message) request,
              (io.grpc.stub.StreamObserver<org.nexus.p2p.NexusChainOuterClass.Message>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class NexusChainBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    NexusChainBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return org.nexus.p2p.NexusChainOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("NexusChain");
    }
  }

  private static final class NexusChainFileDescriptorSupplier
      extends NexusChainBaseDescriptorSupplier {
    NexusChainFileDescriptorSupplier() {}
  }

  private static final class NexusChainMethodDescriptorSupplier
      extends NexusChainBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    NexusChainMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (NexusChainGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new NexusChainFileDescriptorSupplier())
              .addMethod(getEntryMethod())
              .build();
        }
      }
    }
    return result;
  }
}
