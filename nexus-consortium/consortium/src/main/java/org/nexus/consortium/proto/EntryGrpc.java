package org.nexus.consortium.proto;

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
    value = "by gRPC proto compiler (version 1.25.0)",
    comments = "Source: consortium.proto")
public final class EntryGrpc {

  private EntryGrpc() {}

  public static final String SERVICE_NAME = "Entry";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<org.nexus.consortium.proto.Message,
      org.nexus.consortium.proto.Message> getEntryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Entry",
      requestType = org.nexus.consortium.proto.Message.class,
      responseType = org.nexus.consortium.proto.Message.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<org.nexus.consortium.proto.Message,
      org.nexus.consortium.proto.Message> getEntryMethod() {
    io.grpc.MethodDescriptor<org.nexus.consortium.proto.Message, org.nexus.consortium.proto.Message> getEntryMethod;
    if ((getEntryMethod = EntryGrpc.getEntryMethod) == null) {
      synchronized (EntryGrpc.class) {
        if ((getEntryMethod = EntryGrpc.getEntryMethod) == null) {
          EntryGrpc.getEntryMethod = getEntryMethod =
              io.grpc.MethodDescriptor.<org.nexus.consortium.proto.Message, org.nexus.consortium.proto.Message>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Entry"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.nexus.consortium.proto.Message.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.nexus.consortium.proto.Message.getDefaultInstance()))
              .setSchemaDescriptor(new EntryMethodDescriptorSupplier("Entry"))
              .build();
        }
      }
    }
    return getEntryMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EntryStub newStub(io.grpc.Channel channel) {
    return new EntryStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EntryBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new EntryBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EntryFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new EntryFutureStub(channel);
  }

  /**
   */
  public static abstract class EntryImplBase implements io.grpc.BindableService {

    /**
     */
    public io.grpc.stub.StreamObserver<org.nexus.consortium.proto.Message> entry(
        io.grpc.stub.StreamObserver<org.nexus.consortium.proto.Message> responseObserver) {
      return asyncUnimplementedStreamingCall(getEntryMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getEntryMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                org.nexus.consortium.proto.Message,
                org.nexus.consortium.proto.Message>(
                  this, METHODID_ENTRY)))
          .build();
    }
  }

  /**
   */
  public static final class EntryStub extends io.grpc.stub.AbstractStub<EntryStub> {
    private EntryStub(io.grpc.Channel channel) {
      super(channel);
    }

    private EntryStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EntryStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new EntryStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<org.nexus.consortium.proto.Message> entry(
        io.grpc.stub.StreamObserver<org.nexus.consortium.proto.Message> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getEntryMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   */
  public static final class EntryBlockingStub extends io.grpc.stub.AbstractStub<EntryBlockingStub> {
    private EntryBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private EntryBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EntryBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new EntryBlockingStub(channel, callOptions);
    }
  }

  /**
   */
  public static final class EntryFutureStub extends io.grpc.stub.AbstractStub<EntryFutureStub> {
    private EntryFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private EntryFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EntryFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new EntryFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_ENTRY = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final EntryImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(EntryImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_ENTRY:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.entry(
              (io.grpc.stub.StreamObserver<org.nexus.consortium.proto.Message>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class EntryBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EntryBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return org.nexus.consortium.proto.Consortium.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Entry");
    }
  }

  private static final class EntryFileDescriptorSupplier
      extends EntryBaseDescriptorSupplier {
    EntryFileDescriptorSupplier() {}
  }

  private static final class EntryMethodDescriptorSupplier
      extends EntryBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    EntryMethodDescriptorSupplier(String methodName) {
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
      synchronized (EntryGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EntryFileDescriptorSupplier())
              .addMethod(getEntryMethod())
              .build();
        }
      }
    }
    return result;
  }
}
