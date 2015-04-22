import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;


public class RequestWorker extends Thread {
	
	
	int id;
	
	Socket clientSocket; // connection to client
	BufferedReader clientIn; // client -> proxy stream
	DataOutputStream clientOut; // proxy -> client stream
	String method; // HTTP method being used
	
	Socket remoteSocket; // connection to remote
	DataInputStream remoteIn; // remote -> proxy stream
	DataOutputStream remoteOut; // proxy -> remote stream
	Hashtable<String,String> remoteHeaders; // headers received from remote
	
	public RequestWorker(Socket clientSocket, int id) {
		this.clientSocket = clientSocket;
		this.id = id;
	}

	
	public void run() {
		try {
			
			// reader for client->proxy data
			clientIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), Charset.forName("UTF-8")));
			
			// writer for proxy->client data
			clientOut = new DataOutputStream(clientSocket.getOutputStream());
			
			
			String remoteHostname = "";
			int remotePort = 80;
			String remotePath = "";
			method = "";
			
			try {
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
					clientSocket.close();
					return;
				}
				
				// the first line of the request
				String request = lines.get(0);
				
				logConnection(Node.CLIENT, Node.PROXY, request);
				
				String[] requestArgs = request.split(" ");
				if (requestArgs.length != 3) {
					logError("Invalid HTTP header line");
					respondWithHtmlStatus(HttpStatus.BAD_REQUEST);
					return;
				}
				method = requestArgs[0];
				
				// reject methods we don't support
				if (!method.equals("GET") && !method.equals("HEAD")) {
					logError("Invalid HTTP method");
					respondWithHtmlStatus(HttpStatus.METHOD_NOT_ALLOWED);
					return;
				}
				// force http/1.1
				if (!requestArgs[2].equalsIgnoreCase(Utils.HTTP_VERSION)) {
					logError("Invalid HTTP version");
					respondWithHtmlStatus(HttpStatus.HTTP_VER_NOT_SUPPORTED);
					return;
				}
				
				String requestUrl = requestArgs[1]; // the URI that our client wants to access
				
				
				// handle internal local addresses
				if (requestUrl.startsWith("/")) {
					String[] requestParams = requestUrl.split("/");
					
					
					if (requestParams.length <= 1) {
						// a simple homepage
						
						respondWithHtml(HttpStatus.OK, Utils.getSimpleHtml(
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
				remoteHostname = urlMinusProtocol.split("/")[0];
				remotePath = urlMinusProtocol.substring(remoteHostname.length());
				
				
				// pull the port out of the url, if necessary
				String[] hostnameExplode = remoteHostname.split(":");
				if (hostnameExplode.length > 1) {
					remoteHostname = hostnameExplode[0];
					remotePort = Integer.parseInt(hostnameExplode[1]);
				}
				
			} catch (SocketTimeoutException e) {
				logError("Connection to client timed out");
				respondWithHtmlStatus(HttpStatus.REQUEST_TIMEOUT);
				return;
			}
			
			
			
			try {
				remoteSocket = new Socket(remoteHostname, remotePort);
			} catch (UnknownHostException e) {
				logError("Failed to identify remote hostname");
				respondWithHtmlStatus(HttpStatus.BAD_REQUEST);
				return;
			}
			remoteOut = new DataOutputStream(remoteSocket.getOutputStream());
			remoteIn = new DataInputStream(remoteSocket.getInputStream());
			
			String remoteReq = method+" " + remotePath + " " + Utils.HTTP_VERSION;
			logConnection(Node.PROXY, Node.REMOTE, remoteReq);
			
			remoteReq += Utils.httpHeader("Host", remoteHostname);
			remoteReq += Utils.httpHeader("User-Agent", Utils.SERVER_NAME);
			remoteReq += Utils.HTTP_HEADER_END;
			
			remoteOut.writeBytes(remoteReq);
			
			remoteHeaders = new Hashtable<String,String>();
			
			try {
				
				String remoteResponseLine = remoteIn.readLine();
				logConnection(Node.REMOTE, Node.PROXY, remoteResponseLine);
				
				// read all the headers
				if (!readRemoteHeaders()) return;
				
				byte[] remoteBody = new byte[0];
				
				if (method != "HEAD") {
					if (remoteHeaders.containsKey("content-length")) {
						// remote server told us from the get-go how much data to
						// expect, so just read exactly that many bytes
						
						String contentLengthStr = remoteHeaders.get("content-length");
						int contentLength = 0;
						try {
							contentLength = Integer.parseInt(contentLengthStr);
						} catch (NumberFormatException nfe) {
							logError("Couldn't parse content length from remote server");
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
							logError("Unknown transfer-encoding method: " + remoteHeaders.get("transfer-encoding"));
							respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
							return;
						}
						
						String chunkHead;
						while ((chunkHead = remoteIn.readLine()) != null) {
							
							if (chunkHead.equals("0") || chunkHead.equals("")) break;
	
							// get the size of this chunk
							int chunkSize = 0;
							try {
								chunkSize = Integer.parseInt(chunkHead.split(";")[0], 16);
							} catch (NumberFormatException e) {
								logError("Couldn't parse chunk size from remote server");
								respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
								return;
							}
							
							// we need to expand the byte array to accommodate this chunk
							int oldLength = remoteBody.length;
							int newLength = oldLength + chunkSize;
							
							// add this chunk to the byte array
							remoteBody = Arrays.copyOf(remoteBody, newLength);
							for (int i = oldLength; i < newLength; i++) {
								remoteBody[i] = (byte) remoteIn.read();
							}
							
							remoteIn.skipBytes(2); // skip the CRLF at the end of the chunk
						}
						
						remoteHeaders.remove("transfer-encoding");
						remoteHeaders.put("content-length", String.valueOf(remoteBody.length));
						
						// we've reached the end of the chunks, so add footers to our headers array if any exist
						if (!readRemoteHeaders()) return;
						
						
					} else {
						// remote server is sending data using some voodoo magic we don't understand
						logError("Unknown data tranfer method");
						respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
						return;
					}
				}
				
				
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
	
				if (method != "HEAD") clientOut.write(remoteBody);
				
				clientOut.close();
				clientIn.close();
				clientSocket.close();
				
				
			} catch (SocketTimeoutException e) {
				logError("Connection to remote server timed out");
				respondWithHtmlStatus(HttpStatus.GATEWAY_TIMEOUT);
				return;
				
			}
			
			
		} catch (IOException e) {
			if (Utils.LOG_REQUEST_ERRORS) e.printStackTrace();
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
			
			String[] splitLine = remoteLine.split(":");
			if (splitLine.length < 2) {
				logError("Invalid header from remote server: " + remoteLine);
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
		String requestLine = Utils.HTTP_VERSION + " " + status.getFullName();
		
		logConnection(Node.PROXY, Node.CLIENT, requestLine);
		clientOut.writeBytes(requestLine);
		sendHeader("Server", Utils.SERVER_NAME);
		sendHeader("Date", Utils.getRFC1123Date());
	}
	
	/**
	 * Convenience method for sending an HTTP header to the client
	 */
	public void sendHeader(String key, String value) throws IOException {
		clientOut.writeBytes(Utils.httpHeader(key, value));
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
		writeBody(body);
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
	
	public void writeBody(String body) throws IOException {
		if (method != "HEAD") clientOut.writeBytes(body);
	}
	
	
	
	/**
	 * Logs a non-error message about this request
	 */
	public void log(String message) {
		if (Utils.LOG_REQUEST_MESSAGES) log(message, false);
	}
	
	/**
	 * Helper method for other error logging methods (shouldn't be called directly)
	 */
	public void logError(String message) {
		if (Utils.LOG_REQUEST_ERRORS) log(message, true);
	}
	
	/**
	 * Helper method for all log methods (shouldn't be called directly)
	 */
	private void log(String message, boolean isError) {
		message = id + " | " + message;
		if (!isError) Utils.log(message);
		else Utils.logError(message);
	}
	
	/**
	 * Logs a request being sent between servers
	 * 
	 * @param from server sending request
	 * @param to server receiving request
	 * @param message first line of request
	 */
	public void logConnection(Node from, Node to, String message) {
		String bullet = "";
		if ((from != Node.PROXY && to != Node.PROXY) || to == from) {
			bullet = "???????";
		} else {
			if (from == Node.CLIENT || to == Node.CLIENT) {
				if (!Utils.LOG_REQUEST_CLIENT_CONNECTIONS) return;
				bullet += "C";
				if (to == Node.CLIENT) bullet += "<-";
				else bullet += "->";
			} else {
				bullet += "   ";
			}
			bullet += "P";
			if (from == Node.REMOTE || to == Node.REMOTE) {
				if (!Utils.LOG_REQUEST_REMOTE_CONNECTIONS) return;
				if (from == Node.REMOTE) bullet += "<-";
				else bullet += "->";
				bullet += "R";
			} else {
				bullet += "   ";
			}
		}
		
		log(bullet + " | " + message);
	}
}
