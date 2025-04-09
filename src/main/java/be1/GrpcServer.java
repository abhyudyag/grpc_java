package be1;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import com.example.generated.*;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.HttpURLConnection;

public class GrpcServer {

	private static String serviceId;

	public static void main(String[] args) throws IOException, InterruptedException {
		Server server = ServerBuilder.forPort(0)
			.addService(new MemoServiceImpl())
			.addService(new NoteServiceImpl())
			.addService(ProtoReflectionService.newInstance())
			.build()
			.start();

		int actualPort = server.getPort();

		System.out.println("gRPC BE Server running on port: "+actualPort);

		try{
			registerWithConsul(actualPort);
		} catch (Exception e) {
			e.printStackTrace();
		}

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				deregisterFromConsul();
				server.shutdown();
				System.out.println("gRPC server stopped.");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}));

		server.awaitTermination();  // Keeps it running like a daemon
	}

	static class MemoServiceImpl extends MemoServiceGrpc.MemoServiceImplBase {
		@Override
		public void getMemo(MemoRequest request, StreamObserver<MemoResponse> responseObserver) {
			System.out.println("Received request for ID: " + request.getId());

			MemoResponse response = MemoResponse.newBuilder()
				.setMessage("Memo for ID: " + request.getId())
				.build();

			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}
	}

	static class NoteServiceImpl extends NoteServiceGrpc.NoteServiceImplBase {
		@Override
		public void getNote(NoteRequest request, StreamObserver<NoteResponse> responseObserver) {
			NoteResponse response = NoteResponse.newBuilder()
				.setBody("Note body for title: " + request.getTitle())
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}
	}

	private static void registerWithConsul(int port) throws Exception {
		serviceId = "be1-service-" + port;
		String serviceName = "be1-server";
		String address = InetAddress.getLocalHost().getHostAddress();

		String json = """
		{
			"ID": "%s",
				"Name": "%s",
				"Tags": ["MemoService", "NoteService"], 
				"Address": "%s",
				"Port": %d,
				"Check": {
					"TCP": "%s:%d",
					"Interval": "10s",
					"Timeout": "5s"
			}
		}
		""".formatted(serviceId, serviceName, address, port, address, port);

		sendPutRequest("http://localhost:8500/v1/agent/service/register", json);
		System.out.println("Registered with Consul as " + serviceId);
	}

	private static void deregisterFromConsul() throws Exception {
		String url = "http://localhost:8500/v1/agent/service/deregister/" + serviceId;
		sendPutRequest(url, null);
		System.out.println("Deregistered from Consul: " + serviceId);
	}

	private static void sendPutRequest(String urlString, String json) throws Exception {
		URL url = new URL(urlString);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("PUT");
		if (json != null) {
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "application/json");
			try (OutputStream os = conn.getOutputStream()) {
				os.write(json.getBytes());
				os.flush();
			}
		}
		int responseCode = conn.getResponseCode();
		if (responseCode != 200) {
			throw new RuntimeException("Failed to register/deregister with Consul. HTTP code: " + responseCode);
		}
		conn.disconnect();
	}
}
									
