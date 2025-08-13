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


def run_ping(stub):
    print("\n-------------- Ping --------------")
    try:
        response = stub.Ping(admin_pb2.PingRequest())
        print("Response from server: " + response.message)
    except grpc.RpcError as e:
        print(f"An error occurred: {e.code()} - {e.details()}")

def run_stop_engine(stub):
    print("\n-------------- Stop Engine --------------")
    try:
        response = stub.StopEngine(admin_pb2.StopEngineRequest())
        if response.success:
            print("Engine stop command sent successfully.")
            return True
        else:
            print("Failed to send engine stop command.")
            return False
    except grpc.RpcError as e:
        print(f"An error occurred: {e.code()} - {e.details()}")
        return False

def run_add_user(stub):
    print("\n-------------- Add User --------------")
    try:
        uid = int(input("Enter user ID to add: "))
        response = stub.AddUser(admin_pb2.AddUserRequest(uid=uid))
        if response.success:
            print(f"User {uid} added successfully.")
        else:
            print(f"Failed to add user {uid}: {response.message}")
    except ValueError:
        print("Invalid user ID. Please enter an integer.")
    except grpc.RpcError as e:
        print(f"An error occurred: {e.code()} - {e.details()}")

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

        while True:
            print("\nAvailable actions:")
            print("  1. Ping Server")
            print("  2. Stop Trading Engine")
            print("  3. Add User")
            print("  4. Exit")
            choice = input("Enter your choice: ")

            if choice == '1':
                run_ping(authed_stub)
            elif choice == '2':
                if run_stop_engine(authed_stub):
                    print("Exiting client as engine is stopping.")
                    break
            elif choice == '3':
                run_add_user(authed_stub)
            elif choice == '4':
                print("Exiting.")
                break
            else:
                print("Invalid choice, please try again.")


if __name__ == '__main__':
    run()
