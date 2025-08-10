import grpc
import admin_pb2
import admin_pb2_grpc
import getpass

# A client-side interceptor to add the auth token to every RPC call.
class AuthTokenInterceptor(grpc.UnaryUnaryClientInterceptor):
    def __init__(self, token):
        self._token = token

    def intercept_unary_unary(self, continuation, client_call_details, request):
        # Add the auth token to the metadata.
        metadata = []
        if client_call_details.metadata is not None:
            metadata = list(client_call_details.metadata)
        
        # The metadata key must match what the server's AuthInterceptor expects.
        metadata.append(('auth-token', self._token))
        
        new_details = client_call_details._replace(metadata=metadata)
        
        return continuation(new_details, request)

def login(stub):
    print("-------------- Login --------------")
    username = input("Enter username: ")
    password = getpass.getpass("Enter password: ")
    try:
        response = stub.Login(admin_pb2.LoginRequest(username=username, password=password))
        if response.success and response.token:
            print("Login successful!")
            return response.token
        else:
            print(f"Login failed: {response.message}")
            return None
    except grpc.RpcError as e:
        print(f"An error occurred during login: {e.details()}")
        return None


def run():
    server_address = 'localhost:9001'
    
    # First, create an insecure channel for the login RPC
    with grpc.insecure_channel(server_address) as channel:
        stub = admin_pb2_grpc.AdminServiceStub(channel)
        
        # Perform login to get the token
        token = login(stub)
        if not token:
            return

    # Now, create a new channel with an interceptor that adds the token to all calls
    interceptor = AuthTokenInterceptor(token)
    with grpc.insecure_channel(server_address) as channel:
        intercepted_channel = grpc.intercept_channel(channel, interceptor)
        authed_stub = admin_pb2_grpc.AdminServiceStub(intercepted_channel)

        print("\n-------------- Ping (should succeed) --------------")
        try:
            response = authed_stub.Ping(admin_pb2.PingRequest())
            print("Response from server: " + response.message)
        except grpc.RpcError as e:
            print(f"An error occurred: {e.code()} - {e.details()}")

        print("\n-------------- Stop Engine (should succeed) --------------")
        try:
            response = authed_stub.StopEngine(admin_pb2.StopEngineRequest())
            if response.success:
                print("Engine stop command sent successfully.")
            else:
                print("Failed to send engine stop command.")
        except grpc.RpcError as e:
            print(f"An error occurred: {e.code()} - {e.details()}")


if __name__ == '__main__':
    run()
