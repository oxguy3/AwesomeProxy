import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;


public class ProxyServer extends Thread {
	
	final static String HTTP_VERSION = "HTTP/1.1";
	final static String CRLF = "\r\n";
	final static String LF = "\n";

	// should the server respond to HTTP requests at all?
	volatile static boolean isAlive;
	
	// should the server respond to proxy HTTP requests?
	volatile static boolean isProxyActive;
	
	
	Socket server; // connection to client
	BufferedReader buff; // client -> proxy
	DataOutputStream dos; // proxy -> client
	
	Socket client; // connection to remote
	DataInputStream remoteDis; // 
	DataOutputStream remoteDos;
	
	public ProxyServer(Socket srv) {
		server = srv;
	}

	public static void main(String[] args) {
		
		int port = 8080;
		if (args.length > 0) {
			String portStr = args[0];
			port = Integer.parseInt(portStr);
		}
		
		ServerSocket srvSock;
		
		try {
			srvSock = new ServerSocket(port);
			System.out.println("Listening for connections on port " + Integer.toString(port) + "...");
			
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
			
			
		isAlive = true;
		isProxyActive = true;
		
		while (isAlive) {
			try {
				Socket server = srvSock.accept();
				if (!isAlive) break;

				System.out.println("Received new connection...");
				ProxyServer worker = new ProxyServer(server);
				worker.start();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public void run() {
		try {
			
			// reader for client->proxy data
			buff = new BufferedReader(new InputStreamReader(server.getInputStream(), Charset.forName("UTF-8")));
			
			// writer for proxy->client data
			dos = new DataOutputStream(server.getOutputStream());
			
			
			ArrayList<String> lines = new ArrayList<String>();
			
			String line;
			while ((line = buff.readLine()) != null) {
				
				// break after two lines
				if (line.equals("")) break;
				
				System.out.println("C->P | "+line);
				lines.add(line);
			}
			
			// firefox seems to like sending empty requests when it accesses cached files, so let's just ignore those
			if (lines.size() < 1) {
				System.out.println("Closing empty request...");
				server.close();
				return;
			}
			
			// the first line of the request
			String request = lines.get(0);
			
			String[] requestArgs = request.split(" ");
			if (requestArgs.length != 3) {
				respondError(HttpStatus.BAD_REQUEST);
				return;
			}
			// we only support GET
			if (!requestArgs[0].equalsIgnoreCase("GET")) {
				respondError(HttpStatus.METHOD_NOT_ALLOWED);
				return;
			}
			// force http/1.1
			if (!requestArgs[2].equalsIgnoreCase(HTTP_VERSION)) {
				respondError(HttpStatus.HTTP_VER_NOT_SUPPORTED);
				return;
			}
			
			String requestUrl = requestArgs[1]; // the URI that our client wants to access
			
			
			// handle local (i.e. non-remote) addresses
			if (requestUrl.startsWith("/")) {
				String[] requestParams = requestUrl.split("/");
				
				
				if (requestParams.length <= 1) {
					respondWithSimpleHtml(
							HttpStatus.ACCEPTED,
							"Welcome to " + PSUtils.SERVER_NAME + "!",
							"<p>Made by Hayden Schiff and Shir Maimon.</p>"
							+ "<p>Actions available:</p>"
							+ "<ul>"
							+ "<li><a href=\"/proxy/on\">Enable proxy service</a></li>"
							+ "<li><a href=\"/proxy/off\">Disable proxy service</a></li>"
							+ "<li><a href=\"/exit\">Shutdown the server</a></li>"
							+ "</ul>"
					);
					return;
					
				} else if (requestParams[1].equals("exit")) { // command to shutdown the server
					ProxyServer.isAlive = false;
					respondWithSimpleHtmlMessage(
							HttpStatus.ACCEPTED,
							"Exit command received",
							"The server is no longer accepting new connections, and "
							+ "will shutdown after all active connections are closed."
					);
					return;
					
				} else if (requestParams[1].equals("proxy") && requestParams.length > 2) {
					
					// commands to turn proxy service on/off
					
					if (requestParams[2].equals("on")) {
						ProxyServer.isProxyActive = true;
						respondWithSimpleHtmlMessage(
								HttpStatus.ACCEPTED,
								"Proxy enabled",
								"The server will now accept proxy requests."
						);
						return;
						
					} else if (requestParams[2].equals("off")) {
						ProxyServer.isProxyActive = false;
						respondWithSimpleHtmlMessage(
								HttpStatus.ACCEPTED,
								"Proxy disabled",
								"The server will stop accepting proxy requests."
						);
						return;
						
					} 
					
				} else if (requestParams[1].equals("teapot")) {
					
					respondError(HttpStatus.IM_A_TEAPOT);
					return;
					
				}
				
				respondError(HttpStatus.NOT_FOUND);
				return;
			}
			
			// forbid unfamiliar protocols and local files
			if (!requestUrl.startsWith("http://")) {
				respondError(HttpStatus.FORBIDDEN);
				return;
			}
			if (!ProxyServer.isProxyActive) {
				respondError(HttpStatus.SERVICE_UNAVAILABLE);
			}
			
			String urlMinusProtocol = requestUrl.substring("http://".length());
			String hostname = urlMinusProtocol.split("/")[0];
			String requestPath = urlMinusProtocol.substring(hostname.length());
			
			
			// pull the port out of the url, if necessary
			int remotePort = 80;
			String[] hostnameExplode = hostname.split(":");
			if (hostnameExplode.length > 1) {
				hostname = hostnameExplode[0];
				remotePort = Integer.parseInt(hostnameExplode[1]);
			}
			
			
			
			
			client = new Socket(hostname, remotePort);
			System.out.println("Connecting to remote server...");
			remoteDos = new DataOutputStream(client.getOutputStream());
			remoteDis = new DataInputStream(client.getInputStream());
			
			String remoteReq = "";
			
			remoteReq += "GET " + requestPath + " " + HTTP_VERSION + CRLF;
			remoteReq += "Host: " + hostname + CRLF;
			remoteReq += CRLF;
			
			remoteDos.writeBytes(remoteReq);
			System.out.println("Sent request to remote server...");
			
			Hashtable<String,String> remoteHeaders = new Hashtable<String,String>();
			
			String remoteResponseLine = remoteDis.readLine();
			System.out.println("R>P: " + remoteResponseLine);
			
			String remoteLine;
			while ((remoteLine = remoteDis.readLine()) != null) {
				
				// break after two lines
				if (remoteLine.equals("")) break;
				
				System.out.println("R->P | "+remoteLine);
				
				String[] splitLine = remoteLine.split(":");
				if (splitLine.length < 2) {
					respondError(HttpStatus.BAD_GATEWAY);
					return;
				}
				String headerKey = splitLine[0].toLowerCase();
				remoteHeaders.put(headerKey, remoteLine.substring(headerKey.length()+1).trim());
			}
			
			
			String contentLengthStr = remoteHeaders.get("content-length");
			if (contentLengthStr == null) {
				respondError(HttpStatus.BAD_GATEWAY);
				return;
			}
			int contentLength = Integer.parseInt(contentLengthStr);
			
			byte[] remoteBody = new byte[contentLength];
			for (int i = 0; i < contentLength; i++) {
				if (remoteBody[i] == -1) System.err.print("@");
				remoteBody[i] = (byte) remoteDis.read();
			}
			
			//System.out.println(Arrays.toString(remoteBody));
			
			
			System.out.println("Closing remote connection...");
			client.close();
			
			
			

			dos.writeBytes(remoteResponseLine);
			Enumeration<String> keys = remoteHeaders.keys();
			while (keys.hasMoreElements()) {
				String key = keys.nextElement();
				String header = CRLF + key + ": " + remoteHeaders.get(key);
				dos.writeBytes(header);
			}
			dos.writeBytes(CRLF+CRLF);

			dos.write(remoteBody);
			
			dos.flush();
			dos.close();
			
			System.out.println("Closing client connection...");
			server.close();
			
			
			
			
//			dos.writeUTF(HTTP_VERSION + " 200 S'all good");
//			sendObligatoryHeaders(dos);
//			server.close();
			
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void beginResponse(HttpStatus status) throws IOException {
		dos.writeBytes(HTTP_VERSION + " " + status.getFullName());
		sendHeader("Server", PSUtils.SERVER_NAME);
		sendHeader("Date", PSUtils.getRFC1123Date());
	}
	
	public void endHeader() throws IOException {
		dos.writeBytes(CRLF+CRLF);
	}
	
	public void endResponse() throws IOException {
		dos.close();
		if (server != null && !server.isClosed()) server.close();
		if (client != null && !client.isClosed()) client.close();
	}
	
	public void respondWithBody(HttpStatus status, String body) throws IOException {
		respondWithBody(status, body, "text/html");
	}
	
	public void respondWithBody(HttpStatus status, String body, String contentType) throws IOException {
		beginResponse(status);
		sendHeader("Content-Length", String.valueOf(body.length()));
		sendHeader("Content-Type", contentType);
		endHeader();
		dos.writeBytes(body);
		endResponse();
	}
	
	public void respondWithSimpleHtmlMessage(HttpStatus status, String title, String message) throws IOException {
		respondWithBody(status, PSUtils.getSimpleHtmlMessage(title, message));
	}
	
	public void respondWithSimpleHtml(HttpStatus status, String title, String content) throws IOException {
		respondWithBody(status, PSUtils.getSimpleHtml(title, content));
	}
	
	public void respondError(HttpStatus status) throws IOException {
		
		String body = PSUtils.getSimpleHtmlMessage(status.getFullName(), status.description);
		respondWithBody(status, body);
	}
	
	public void sendHeader(String key, String value) throws IOException {
		dos.writeBytes(CRLF + key + ": " + value);
	}

}
