import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;


public class ProxyServer extends Thread {

	// should the server respond to HTTP requests at all?
	volatile static boolean isAlive;
	
	// should the server respond to proxy HTTP requests?
	volatile static boolean isProxyActive;

	public static void main(String[] args) {
		
		int port = 8080;
		if (args.length > 0) {
			String portStr = args[0];
			port = Integer.parseInt(portStr);
		}
		
		// start listening for connections
		ServerSocket srvSock;
		try {
			srvSock = new ServerSocket(port);
			Utils.log("Listening for connections on port " + Integer.toString(port) + "...");
			
		} catch (IOException e) {
			Utils.logError("Failed to bind socket");
			e.printStackTrace();
			return;
		}
			
			
		isAlive = true;
		isProxyActive = true;
		
		int ct = -1;
		
		while (isAlive) {
			ct++;
			try {
				Socket server = srvSock.accept();
				if (!isAlive) break;
				
				RequestWorker worker = new RequestWorker(server, ct);
				worker.start();
			} catch (IOException e) {
				Utils.logError("Failed to accept request");
			}
		}
		
		try {
			srvSock.close();
			Utils.log("Server shutting down...");
		} catch (IOException e) {
			Utils.logError("Failed to shut down server");
		}
	}

}
