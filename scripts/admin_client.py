import grpc
import admin_pb2
import admin_pb2_grpc
import getpass
import threading

# A client-side interceptor to add the auth token to every RPC call.
class AuthTokenInterceptor(grpc.UnaryUnaryClientInterceptor, grpc.UnaryStreamClientInterceptor):
    def __init__(self, token):
        self._token = token

    def _intercept_call(self, client_call_details):
        metadata = []
        if client_call_details.metadata is not None:
            metadata = list(client_call_details.metadata)
        
        metadata.append(('auth-token', self._token))
        
        return client_call_details._replace(metadata=metadata)

    def intercept_unary_unary(self, continuation, client_call_details, request):
        new_details = self._intercept_call(client_call_details)
        return continuation(new_details, request)

    def intercept_unary_stream(self, continuation, client_call_details, request):
        new_details = self._intercept_call(client_call_details)
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
        stub.addUser(admin_pb2.AddUserRequest(uid=uid))
        print(f"Request to add user {uid} sent. Check event stream for result.")
    except ValueError:
        print("Invalid user ID. Please enter an integer.")
    except grpc.RpcError as e:
        print(f"An error occurred: {e.code()} - {e.details()}")

def run_subscribe_events(stub):
    print("\n-------------- Subscribing to Admin Events --------------")
    try:
        events = stub.subscribeAdminEvents(admin_pb2.SubscribeAdminEventsRequest(lastEventIndex=0))
        for event in events:
            print(f"Received Event (Index: {event.index}): {event}")
    except grpc.RpcError as e:
        print(f"An error occurred while subscribing to events: {e.code()} - {e.details()}")

def run():
    server_address = 'localhost:9001'
    
    with grpc.insecure_channel(server_address) as channel:
        stub = admin_pb2_grpc.AdminServiceStub(channel)
        
        token = login(stub)
        if not token:
            return

    interceptor = AuthTokenInterceptor(token)
    with grpc.insecure_channel(server_address) as channel:
        intercepted_channel = grpc.intercept_channel(channel, interceptor)
        authed_stub = admin_pb2_grpc.AdminServiceStub(intercepted_channel)

        # Start event subscription in a background thread
        event_thread = threading.Thread(target=run_subscribe_events, args=(authed_stub,), daemon=True)
        event_thread.start()

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
