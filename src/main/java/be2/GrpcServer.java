package be2;

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
			.addService(new NameServiceImpl())
			.addService(new AgeServiceImpl())
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

	static class NameServiceImpl extends NameServiceGrpc.NameServiceImplBase {
		@Override
		public void getName(NameRequest request, StreamObserver<NameResponse> responseObserver) {
			System.out.println("Received request for ID: " + request.getText());

			NameResponse response = NameResponse.newBuilder()
				.setMessage("Name is: " + request.getText())
				.build();

			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}
	}

	static class AgeServiceImpl extends AgeServiceGrpc.AgeServiceImplBase {
		@Override
		public void getAge(AgeRequest request, StreamObserver<AgeResponse> responseObserver) {
			AgeResponse response = AgeResponse.newBuilder()
				.setBody("Age is: " + request.getNum())
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}
	}

	private static void registerWithConsul(int port) throws Exception {
		serviceId = "be2-service-" + port;
		String serviceName = "be2-server";
		String address = InetAddress.getLocalHost().getHostAddress();

		String json = """
		{
			"ID": "%s",
				"Name": "%s",
				"Tags": ["NameService", "AgeService"], 
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
									
