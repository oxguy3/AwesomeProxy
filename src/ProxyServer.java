import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;


public class ProxyServer extends Thread {

	// should the server respond to HTTP requests at all?
	volatile static boolean isAlive;
	
	// should the server respond to proxy HTTP requests?
	volatile static boolean isProxyActive;
	
	
	Socket clientSocket; // connection to client
	BufferedReader clientIn; // client -> proxy
	DataOutputStream clientOut; // proxy -> client
	
	Socket remoteSocket; // connection to remote
	DataInputStream remoteIn; // remote -> proxy
	DataOutputStream remoteOut; // proxy -> remote
	Hashtable<String,String> remoteHeaders; // headers received from remote
	
	public ProxyServer(Socket srv) {
		clientSocket = srv;
	}

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
			System.out.println("Listening for connections on port " + Integer.toString(port) + "...");
			
		} catch (IOException e) {
			System.err.println("Failed to bind socket");
			e.printStackTrace();
			return;
		}
			
			
		isAlive = true;
		isProxyActive = true;
		
		while (isAlive) {
			try {
				Socket server = srvSock.accept();
				if (!isAlive) break;

				//System.out.println("Received new connection...");
				ProxyServer worker = new ProxyServer(server);
				worker.start();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		try {
			srvSock.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void run() {
		try {
			
			// reader for client->proxy data
			clientIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), Charset.forName("UTF-8")));
			
			// writer for proxy->client data
			clientOut = new DataOutputStream(clientSocket.getOutputStream());
			
			
			ArrayList<String> lines = new ArrayList<String>();
			
			String line;
			while ((line = clientIn.readLine()) != null) {
				
				// break after two lines
				if (line.equals("")) break;
				
				//System.out.println("C->P | "+line);
				lines.add(line);
			}
			
			// ignore empty requests
			if (lines.size() < 1) {
				System.out.println("Closing empty request...");
				clientSocket.close();
				return;
			}
			
			// the first line of the request
			String request = lines.get(0);
			
			System.out.println("> " + request);
			
			String[] requestArgs = request.split(" ");
			if (requestArgs.length != 3) {
				respondWithHtmlStatus(HttpStatus.BAD_REQUEST);
				return;
			}
			// we only support GET
			if (!requestArgs[0].equalsIgnoreCase("GET")) {
				respondWithHtmlStatus(HttpStatus.METHOD_NOT_ALLOWED);
				return;
			}
			// force http/1.1
			if (!requestArgs[2].equalsIgnoreCase(Utils.HTTP_VERSION)) {
				respondWithHtmlStatus(HttpStatus.HTTP_VER_NOT_SUPPORTED);
				return;
			}
			
			String requestUrl = requestArgs[1]; // the URI that our client wants to access
			
			
			// handle internal local addresses
			if (requestUrl.startsWith("/")) {
				String[] requestParams = requestUrl.split("/");
				
				
				if (requestParams.length <= 1) {
					// a simple homepage
					
					respondWithHtml(HttpStatus.ACCEPTED, Utils.getSimpleHtml(
							"Welcome to " + Utils.SERVER_NAME + "!",
							"<p>Made by Hayden Schiff and Shir Maimon.</p>"
							+ "<p>Actions available:</p>"
							+ "<ul>"
							+ "<li><a href=\"/proxy/on\">Enable proxy service</a></li>"
							+ "<li><a href=\"/proxy/off\">Disable proxy service</a></li>"
							+ "<li><a href=\"/exit\">Shutdown the server</a></li>"
							+ "</ul>"
					));
					return;
					
				} else if (requestParams[1].equals("exit")) {
					// command to shutdown the server
					
					ProxyServer.isAlive = false;
					respondWithMessage(
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
						respondWithMessage(
								HttpStatus.ACCEPTED,
								"Proxy enabled",
								"The server will now accept proxy requests."
						);
						return;
						
					} else if (requestParams[2].equals("off")) {
						ProxyServer.isProxyActive = false;
						respondWithMessage(
								HttpStatus.ACCEPTED,
								"Proxy disabled",
								"The server will stop accepting proxy requests."
						);
						return;
						
					} 
					
				} else if (requestParams[1].equals("teapot")) {
					// i couldn't resist
					
					respondWithHtmlStatus(HttpStatus.IM_A_TEAPOT);
					return;
					
				}
				
				respondWithHtmlStatus(HttpStatus.NOT_FOUND);
				return;
			}
			
			// forbid unfamiliar protocols and local files
			if (!requestUrl.startsWith("http://")) {
				respondWithHtmlStatus(HttpStatus.FORBIDDEN);
				return;
			}
			if (!ProxyServer.isProxyActive) {
				respondWithHtmlStatus(HttpStatus.SERVICE_UNAVAILABLE);
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
			
			
			
			
			remoteSocket = new Socket(hostname, remotePort);
			System.out.println("Connecting to remote server...");
			remoteOut = new DataOutputStream(remoteSocket.getOutputStream());
			remoteIn = new DataInputStream(remoteSocket.getInputStream());
			
			String remoteReq = "";
			
			remoteReq += "GET " + requestPath + " " + Utils.HTTP_VERSION;
			remoteReq += Utils.httpHeader("Host", hostname);
			remoteReq += Utils.httpHeader("User-Agent", Utils.SERVER_NAME);
			remoteReq += Utils.HTTP_HEADER_END;
			
			remoteOut.writeBytes(remoteReq);
			System.out.println("Sent request to remote server...");
			
			remoteHeaders = new Hashtable<String,String>();
			
			String remoteResponseLine = remoteIn.readLine();
			System.out.println("< " + remoteResponseLine);
			//System.out.println("R->P | " + remoteResponseLine);
			
			// read all the headers
			if (!readRemoteHeaders()) return;
			
			byte[] remoteBody = null;
			
			
			if (remoteHeaders.containsKey("content-length")) {
				// remote server told us from the get-go how much data to
				// expect, so just read exactly that many bytes
				
				String contentLengthStr = remoteHeaders.get("content-length");
				int contentLength = 0;
				try {
					contentLength = Integer.parseInt(contentLengthStr);
				} catch (NumberFormatException nfe) {
					System.err.println("Couldn't parse content length from remote server");
					respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
					return;
				}
				
				remoteBody = new byte[contentLength];
				for (int i = 0; i < contentLength; i++) {
					remoteBody[i] = (byte) remoteIn.read();
				}
				
				
			} else if (remoteHeaders.containsKey("transfer-encoding")) {
				// remote server is sending data in chunks, so read all the chunks
				
				// we do not support any transfer-encoding methods besides chunked
				if (!remoteHeaders.get("transfer-encoding").equalsIgnoreCase("chunked")) {
					System.err.println("Unknown transfer-encoding method: " + remoteHeaders.get("transfer-encoding"));
					respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
					return;
				}
				
				remoteBody = new byte[0];
				
				String chunkHead;
				while ((chunkHead = remoteIn.readLine()) != null) {
					
					if (chunkHead.equals("0") || chunkHead.equals("")) break;
					
					int chunkSize = 0;
					try {
						// get the size of this chunk
						chunkSize = Integer.parseInt(chunkHead.split(";")[0], 16);
					} catch (NumberFormatException e) {
						System.err.println("Couldn't parse chunk size from remote server");
						respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
						return;
					}
					
					int oldLength = remoteBody.length;
					int newLength = oldLength + chunkSize;
					
					remoteBody = Arrays.copyOf(remoteBody, newLength);
					for (int i = oldLength; i < newLength; i++) {
						remoteBody[i] = (byte) remoteIn.read();
					}
					remoteIn.skipBytes(2); // skip the CRLF
				}
				
				remoteHeaders.remove("transfer-encoding");
				remoteHeaders.put("content-length", String.valueOf(remoteBody.length));
				
				// we've reached the end of the chunks, so add footers to our headers array if any exist
				if (!readRemoteHeaders()) return;
				
				
			} else {
				// remote server is sending data using some voodoo magic we don't understand
				System.err.println("Unknown data tranfer method");
				respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
				return;
			}
			
			
			//System.out.println("Closing remote connection...");
			remoteOut.close();
			remoteIn.close();
			remoteSocket.close();
			
			
			

			clientOut.writeBytes(remoteResponseLine);
			Enumeration<String> keys = remoteHeaders.keys();
			while (keys.hasMoreElements()) {
				String key = keys.nextElement();
				clientOut.writeBytes(Utils.httpHeader(key, remoteHeaders.get(key)));
			}
			clientOut.writeBytes(Utils.HTTP_HEADER_END);

			clientOut.write(remoteBody);
			
			//System.out.println("Closing client connection...");
			clientOut.close();
			clientIn.close();
			clientSocket.close();
			
			
			
			
//			dos.writeUTF(HTTP_VERSION + " 200 S'all good");
//			sendObligatoryHeaders(dos);
//			server.close();
			
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Reads headers from remote server until end of header block is reached
	 * 
	 * @return success
	 */
	public boolean readRemoteHeaders() throws IOException {
		String remoteLine;
		while ((remoteLine = remoteIn.readLine()) != null) {
			
			if (remoteLine.equals("")) break;
			
			//System.out.println("R->P | "+remoteLine);
			
			String[] splitLine = remoteLine.split(":");
			if (splitLine.length < 2) {
				System.err.println("Invalid header from remote server: " + remoteLine);
				respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
				return false;
			}
			String headerKey = splitLine[0].toLowerCase();
			remoteHeaders.put(headerKey, remoteLine.substring(headerKey.length()+1).trim());
		}
		return true;
	}
	
	/**
	 * Begins to send the header block to the client
	 */
	public void beginResponse(HttpStatus status) throws IOException {
		System.err.println(status.getFullName());
		clientOut.writeBytes(Utils.HTTP_VERSION + " " + status.getFullName());
		sendHeader("Server", Utils.SERVER_NAME);
		sendHeader("Date", Utils.getRFC1123Date());
	}
	
	/**
	 * Finishes the header block being sent to the client
	 */
	public void endHeader() throws IOException {
		clientOut.writeBytes(Utils.HTTP_HEADER_END);
	}
	
	/**
	 * Closes up all open connections for this request
	 */
	public void endResponse() throws IOException {
		clientOut.close();
		if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
		if (remoteSocket != null && !remoteSocket.isClosed()) remoteSocket.close();
	}
	
	/**
	 * Responds to client with some html text
	 */
	public void respondWithHtml(HttpStatus status, String body) throws IOException {
		beginResponse(status);
		sendHeader("Content-Length", String.valueOf(body.length()));
		sendHeader("Content-Type", "text/html");
		endHeader();
		clientOut.writeBytes(body);
		endResponse();
	}
	
	/**
	 * Responds to client with simple HTML page with a title and a message
	 */
	public void respondWithMessage(HttpStatus status, String title, String message) throws IOException {
		respondWithHtml(status, Utils.getSimpleHtmlMessage(title, message));
	}
	
	/**
	 * Responds to client with generic message for given HTTP status
	 */
	public void respondWithHtmlStatus(HttpStatus status) throws IOException {
		String body = Utils.getSimpleHtmlMessage(status.getFullName(), status.description);
		respondWithHtml(status, body);
	}
	
	/**
	 * Convenience method for sending an HTTP header to the client
	 */
	public void sendHeader(String key, String value) throws IOException {
		clientOut.writeBytes(Utils.httpHeader(key, value));
	}

}
