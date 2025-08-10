import grpc
import admin_pb2
import admin_pb2_grpc
import getpass

def login(stub):
    print("-------------- Login --------------")
    username = input("Enter username: ")
    password = getpass.getpass("Enter password: ")
    try:
        response, call = stub.Login.with_call(admin_pb2.LoginRequest(username=username, password=password))
        if response.success:
            print("Login successful!")
            # Extract the session token (or other metadata) from the response headers
            # and use it to create a new channel with credentials.
            # For this example, we'll assume no token is needed and the interceptor works on the server side.
            return stub
        else:
            print(f"Login failed: {response.message}")
            return None
    except grpc.RpcError as e:
        print(f"An error occurred during login: {e.details()}")
        return None


def run():
    # NOTE: The server address and port should match your gRPC server.
    with grpc.insecure_channel('localhost:9001') as channel:
        stub = admin_pb2_grpc.AdminServiceStub(channel)

        stub = login(stub)
        if not stub:
            return

        print("\n-------------- Ping --------------")
        try:
            response = stub.Ping(admin_pb2.PingRequest())
            print("Response from server: " + response.message)
        except grpc.RpcError as e:
            print(f"An error occurred: {e.details()}")


if __name__ == '__main__':
    run()
