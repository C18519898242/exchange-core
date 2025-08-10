import grpc
import admin_pb2
import admin_pb2_grpc

def run():
    # NOTE: The server address and port should match your gRPC server.
    with grpc.insecure_channel('localhost:9001') as channel:
        stub = admin_pb2_grpc.AdminServiceStub(channel)
        print("-------------- Ping --------------")
        response = stub.Ping(admin_pb2.PingRequest())
        print("Response from server: " + response.message)

if __name__ == '__main__':
    run()
