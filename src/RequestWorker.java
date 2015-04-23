import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;


public class RequestWorker extends Thread {
	
	int id; // a unique number identifying this RequestWorker; used for logging
	
	String method; // HTTP method being used
	String host; // hostname that our client is connecting to us at
	
	Socket clientSocket; // connection to client
	DataInputStream clientIn; // client -> proxy stream
	DataOutputStream clientOut; // proxy -> client stream
	Hashtable<String,String> clientHeaders; // headers received from client
	byte[] clientBody; // body received from client
	
	Socket remoteSocket; // connection to remote
	DataInputStream remoteIn; // remote -> proxy stream
	DataOutputStream remoteOut; // proxy -> remote stream
	Hashtable<String,String> remoteHeaders; // headers received from remote
	byte[] remoteBody; // body received from remote
	
	public RequestWorker(Socket clientSocket, int id) {
		this.clientSocket = clientSocket;
		this.id = id;
	}

	
	public void run() {
		try {
			
			// reader for client->proxy data
			clientIn = new DataInputStream(clientSocket.getInputStream());
			
			// writer for proxy->client data
			clientOut = new DataOutputStream(clientSocket.getOutputStream());
			
			
			String remoteHostname = "";
			int remotePort = 80;
			String remotePath = "";
			method = "";
			
			try {
				
				String request = clientIn.readLine();
				
				// ignore empty requests
				if (request == null) {
					clientSocket.close();
					return;
				}
				
				logConnection(Node.CLIENT, Node.PROXY, request);
				
				
				clientHeaders = new Hashtable<String,String>();
				if (!readClientHeaders()) return;
				
				
				String[] requestArgs = request.split(" ");
				if (requestArgs.length != 3) {
					logError("Invalid HTTP header line");
					respondWithHtmlStatus(HttpStatus.BAD_REQUEST);
					return;
				}
				method = requestArgs[0];
				
				// reject methods we don't support
				if (!(method.equalsIgnoreCase("GET")
						|| (method.equalsIgnoreCase("HEAD") && !Utils.STICK_TO_THE_SCRIPT)
						|| (method.equalsIgnoreCase("POST") && !Utils.STICK_TO_THE_SCRIPT))) {
					logError("Invalid or unimplemented HTTP method");
					respondWithHtmlStatus(
							(Utils.STICK_TO_THE_SCRIPT) ? (HttpStatus.METHOD_NOT_ALLOWED) : (HttpStatus.NOT_IMPLEMENTED));
					return;
				}
				// force http/1.1
				if (!requestArgs[2].equalsIgnoreCase(Utils.HTTP_VERSION)) {
					logError("Invalid HTTP version");
					respondWithHtmlStatus(HttpStatus.HTTP_VER_NOT_SUPPORTED);
					return;
				}
				
				// http/1.1 requires the host header be set
				host = clientHeaders.get("host");
				if (host == null) {
					logError("No host specified in request");
					respondWithHtmlStatus(HttpStatus.BAD_REQUEST);
					return;
				}
				
				if (method.equalsIgnoreCase("POST")) {
					if (!readClientBody()) return;
				}
				
				String requestUrl = requestArgs[1]; // the URI that our client wants to access
				
				
				// handle internal local addresses
				if (requestUrl.startsWith("/")) {
					
					requestUrl = URLDecoder.decode(requestUrl, "UTF-8");
					
					if (!Utils.ENABLE_INTERNAL_SERVER) {
						logError("Tried to access internal server while disabled");
						respondWithHtmlStatus(HttpStatus.SERVICE_UNAVAILABLE);
						return;
					}
					
					if (method.equalsIgnoreCase("POST")) {
						respondMethodNotAllowed("GET, HEAD");
					}
					
					String[] requestParams = requestUrl.split("/");
					
					
					if (requestParams.length <= 1) {
						// a simple homepage
						
						String body = "<div class=\"col-md-12\">"
								+ "<p class=\"text-center\">Made by Hayden Schiff and Shir Maimon</p>"
								+ "</div>";
						if (Utils.ENABLE_STATIC_FILES && Utils.ENABLE_DIRECTORY_INDEXING) {
							body += "<div class=\"col-sm-6 col-sm-offset-3\"><p>"
									+ "<a class=\"btn btn-primary btn-lg btn-block\" href=\"/index\">View file index</a>"
									+ "</p></div>";
						}
						if (Utils.ENABLE_INTERNAL_ACTIONS) {
							body += "<div class=\"col-sm-6 col-sm-offset-3\"><p>"
									+ "<a class=\"btn btn-primary btn-lg btn-block\" href=\"/proxy/on\">Enable proxy service</a>"
									+ "</p></div>"
									+ "<div class=\"col-sm-6 col-sm-offset-3\"><p>"
									+ "<a class=\"btn btn-primary btn-lg btn-block\" href=\"/proxy/off\">Disable proxy service</a>"
									+ "</p></div>"
									+ "<div class=\"col-sm-6 col-sm-offset-3\"><p>"
									+ "<a class=\"btn btn-primary btn-lg btn-block\" href=\"/exit\">Shut down the server</a>"
									+ "</p></div>";
						}
						body += "<div class=\"clearfix\"></div>";
						
						respondWithHtml(HttpStatus.OK, Utils.getSimpleHtml(
								"Welcome to " + Utils.SERVER_NAME + "!",
								body,
								".page-header h1, footer { text-align:center; }"
						));
						return;
						
					} else if (requestParams[1].equals("exit")) {
						// command to shutdown the server
						
						if (!Utils.ENABLE_INTERNAL_ACTIONS) {
							respondWithHtmlStatus(HttpStatus.FORBIDDEN);
							return;
						}
						
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
						
						if (!Utils.ENABLE_INTERNAL_ACTIONS) {
							respondWithHtmlStatus(HttpStatus.FORBIDDEN);
							return;
						}
						
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
						
					} else {
						// get file from local filesystem
						
						if (!Utils.ENABLE_STATIC_FILES) {
							respondWithHtmlStatus(HttpStatus.FORBIDDEN);
							return;
						}
						
						if (requestUrl.equals("/index")) requestUrl = "/";
						
						File file = new File(Utils.DOCUMENT_ROOT + requestUrl);
						
						// return a folder index if it's a directory
						if (file.isDirectory()) {
							
							if (!Utils.ENABLE_DIRECTORY_INDEXING) {
								respondWithHtmlStatus(HttpStatus.FORBIDDEN);
								return;
							}
							
							if (!requestUrl.endsWith("/")) {
								respondRedirect(requestUrl +"/");
							}
							
							File[] contents = file.listFiles();
							
							String body = "<div class=\"col-md-12\">"
									+ "<p><a href=\"../\">&laquo; Go up a level</a></p>";
							
							if (contents.length > 0) {
								body += "<ul>";
								for (File f : contents) {
									String path = f.getName();
									if (f.isDirectory()) path += "/";
									body += "<li>"
											+ "<a href=\"" + path + "\">" + path + "</a>"
											+ "</li>";
								}
								body += "</ul>";
							} else {
								body += "<p>This directory is empty.</p>";
							}
							
							body += "</div>";

							respondWithHtml(HttpStatus.OK, Utils.getSimpleHtml(
									"Index of " + requestUrl, body, ""
							));
							
							return;
						}
						// not found if file doesn't exist
						if (!file.exists()) {
							respondWithHtmlStatus(HttpStatus.NOT_FOUND);
							return;
						}

						
						FileInputStream fileIn = new FileInputStream(file);
						//byte[] fileData = new byte[0];
						byte[] fileBuffer = new byte[Utils.FILE_BUFFER_SIZE];
						int fileBufferSize = 0;
						
						// try to get the content type
						String fileName = file.getName();
						String fileExt = fileName.substring(fileName.lastIndexOf(".")+1);
						String fileContentType = Utils.getMimetypeForExtension(fileExt);//Files.probeContentType(requestedFile.toPath());
						
						// start talking to the client
						beginResponse(HttpStatus.OK);
						if (fileContentType != null) {
							sendHeader("Content-Type", fileContentType);
						}
						
						// for simplicity's sake, we'll use chunked encoding every time
						sendHeader("Transfer-Encoding", "chunked");
						endHeader();

						while ((fileBufferSize = fileIn.read(fileBuffer)) != -1) {
							
							writeClientBody(Integer.toString(fileBufferSize, 16) + Utils.CRLF);
							writeClientBody(Arrays.copyOf(fileBuffer, fileBufferSize));
							writeClientBody(Utils.CRLF);
						}
						// signal the end of chunks
						writeClientBody("0" + Utils.CRLF + Utils.CRLF);
						endResponse();
						
						fileIn.close();
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
				logError("Unknown remote hostname");
				respondWithHtmlStatus(HttpStatus.BAD_REQUEST);
				return;
			}
			remoteOut = new DataOutputStream(remoteSocket.getOutputStream());
			remoteIn = new DataInputStream(remoteSocket.getInputStream());
			
			String remoteReq = method+" " + remotePath + " " + Utils.HTTP_VERSION;
			logConnection(Node.PROXY, Node.REMOTE, remoteReq);
			
			// Host header required for http/1.1
			remoteReq += Utils.httpHeader("Host", remoteHostname);
			
			// Via header to identify ourselves as a proxy
			String viaPrefix = (clientHeaders.containsKey("Via")) ? (clientHeaders.get("Via") + ", ") : "";
			remoteReq += Utils.httpHeader("Via", viaPrefix + "1.1 " + Utils.SERVER_NAME);
			
			// must pass length for POST
			if (method.equalsIgnoreCase("POST")) {
				String contentLength = (clientHeaders.containsKey("Content-Length")) ? 
						(clientHeaders.get("Content-Length") + ", ") : String.valueOf(clientBody.length);
				remoteReq += Utils.httpHeader("Content-Length", contentLength);
			}
			
			
			// some headers should be copied from the C->P request to the P->R request
			String[] copyHeaders = {
					"Accept", 
					"Accept-Charset", 
					"Accept-Encoding", 
					"Accept-Language", 
					"Accept-Ranges",
					"Authorization",
					"Cache-Control",
					"Content-Type",
					"Cookie",
					"DNT", //do not track flag
					"From",
					"Referer",
					"User-Agent"
					};
			for (String copyKey : copyHeaders) {
				if (clientHeaders.containsKey(copyKey.toLowerCase())) {
					
					// multiple headers of the same name are separated
					// by CRLFs, so we need to split them back out
					String valueStr = clientHeaders.get(copyKey.toLowerCase());
					String[] values = valueStr.split(Utils.CRLF);
					for (String value : values) {
						remoteReq += Utils.httpHeader(copyKey, value);
					}
				}
			}
			
			remoteReq += Utils.HTTP_HEADER_END;
			
			writeRemoteBody(remoteReq);
			
			if (method.equalsIgnoreCase("POST")) {
				writeRemoteBody(clientBody);
			}
			
			
			remoteHeaders = new Hashtable<String,String>();
			
			try {
				
				String remoteResponseLine = remoteIn.readLine();
				logConnection(Node.REMOTE, Node.PROXY, remoteResponseLine);
				
				// read all the headers
				if (!readRemoteHeaders()) return;
				
				//byte[] remoteBody = new byte[0];
				
				if (method != "HEAD") {
					if (!readRemoteBody()) return;
				}
				
				
				remoteOut.close();
				remoteIn.close();
				remoteSocket.close();
				
				

				clientOut.writeBytes(remoteResponseLine);
				Enumeration<String> keys = remoteHeaders.keys();
				while (keys.hasMoreElements()) {
					String key = keys.nextElement();
					
					// multiple headers of the same name are separated
					// by CRLFs, so we need to split them back out
					String valueStr = remoteHeaders.get(key);
					String[] values = valueStr.split(Utils.CRLF);
					for (String value : values) {
						clientOut.writeBytes(Utils.httpHeader(key, value));
					}
				}
				clientOut.writeBytes(Utils.HTTP_HEADER_END);
				
				writeClientBody(remoteBody);
				
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
	 * Reads headers from client until end of header block is reached
	 * 
	 * @return success
	 */
	public boolean readClientHeaders() throws IOException {
		return readHeaders(false);
	}
	
	/**
	 * Reads headers from remote server until end of header block is reached
	 * 
	 * @return success
	 */
	public boolean readRemoteHeaders() throws IOException {
		return readHeaders(true);
	}
	
	/**
	 * Reads headers from remote/client server until end of header block is reached
	 * 
	 * @param isRemote if true, remote; if false, client
	 * @return success
	 */
	public boolean readHeaders(boolean isRemote) throws IOException {
		String remoteLine;
		while ((remoteLine = (isRemote ? remoteIn : clientIn).readLine()) != null) {
			
			if (remoteLine.equals("")) break;
			
			String[] splitLine = remoteLine.split(":");
			if (splitLine.length < 2) {
				logError("Invalid header from remote server: " + remoteLine);
				respondWithHtmlStatus(HttpStatus.BAD_GATEWAY);
				return false;
			}
			String headerKey = splitLine[0].toLowerCase();
			String headerValue = remoteLine.substring(headerKey.length()+1).trim();
			Hashtable<String,String> headers = (isRemote ? remoteHeaders : clientHeaders);
			
			// multiple of the same header may exist for comma-separated header types
			if (headers.containsKey(headerKey)) {
				headerValue = headers.get(headerKey) + Utils.CRLF + headerValue;
			}
			headers.put(headerKey, headerValue);
		}
		return true;
	}
	
	
	public boolean readClientBody() throws IOException {
		return readBody(false);
	}
	
	public boolean readRemoteBody() throws IOException {
		return readBody(true);
	}

	/**
	 * Reads body from remote/client server until end of body is reached
	 * 
	 * @param isRemote if true, remote; if false, client
	 * @return success
	 */
	private boolean readBody(boolean isRemote) throws IOException {
		
		Hashtable<String,String> headers = isRemote ? remoteHeaders : clientHeaders;
		byte[] body = isRemote ? remoteBody : clientBody;
		DataInputStream in = isRemote ? remoteIn : clientIn;
		String nodeName = isRemote ? "remote server" : "client";
		HttpStatus httpStatusBadBody = isRemote ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
		
		if (body == null) body = new byte[0];

		if (headers.containsKey("content-length")) {
			// they told us from the get-go how much data to
			// expect, so just read exactly that many bytes
			
			String contentLengthStr = headers.get("content-length");
			int contentLength = 0;
			try {
				contentLength = Integer.parseInt(contentLengthStr);
			} catch (NumberFormatException nfe) {
				logError("Couldn't parse content length from " + nodeName);
				respondWithHtmlStatus(httpStatusBadBody);
				return false;
			}
			
			body = new byte[contentLength];
			for (int i = 0; i < contentLength; i++) {
				body[i] = (byte) in.read();
			}
			
			
		} else if (headers.containsKey("transfer-encoding")) {
			// they're sending data in chunks, so read all the chunks
			
			// we do not support any transfer-encoding methods besides chunked
			if (!headers.get("transfer-encoding").equalsIgnoreCase("chunked")) {
				logError("Unknown transfer-encoding method from "
						+ nodeName + ": " + headers.get("transfer-encoding"));
				respondWithHtmlStatus(httpStatusBadBody);
				return false;
			}
			
			String chunkHead;
			while ((chunkHead = remoteIn.readLine()) != null) {
				
				if (chunkHead.equals("0") || chunkHead.equals("")) break;

				// get the size of this chunk
				int chunkSize = 0;
				try {
					chunkSize = Integer.parseInt(chunkHead.split(";")[0], 16);
				} catch (NumberFormatException e) {
					logError("Couldn't parse chunk size from " + nodeName);
					respondWithHtmlStatus(httpStatusBadBody);
					return false;
				}
				
				// we need to expand the byte array to accommodate this chunk
				int oldLength = body.length;
				int newLength = oldLength + chunkSize;
				
				// add this chunk to the byte array
				body = Arrays.copyOf(body, newLength);
				for (int i = oldLength; i < newLength; i++) {
					body[i] = (byte) in.read();
				}
				
				in.skipBytes(2); // skip the CRLF at the end of the chunk
			}
			
			headers.remove("transfer-encoding");
			headers.put("content-length", String.valueOf(body.length));
			
			// we've reached the end of the chunks, so add footers to our headers array if any exist
			if (!readHeaders(isRemote)) return false;
			
			
		} else {
			// no content received (or at least none we know how to read)
			
//			// they're sending data using some voodoo magic we don't understand
//			logError("Unknown data tranfer method from " + nodeName);
//			respondWithHtmlStatus(httpStatusBadBody);
//			return false;
		}
		
		if (isRemote) {
			remoteHeaders = headers;
			remoteBody = body;
			remoteIn = in;
		} else {
			clientHeaders = headers;
			clientBody = body;
			clientIn = in;
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
		writeClientBody(body);
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
	 * Responds to client that tried to use a method that isn't allowed
	 */
	public void respondMethodNotAllowed(String allowedMethods) throws IOException {
		HttpStatus status = HttpStatus.METHOD_NOT_ALLOWED;
		String body = Utils.getSimpleHtmlMessage(status.getFullName(), status.description);
		beginResponse(status);
		sendHeader("Content-Length", String.valueOf(body.length()));
		sendHeader("Content-Type", "text/html");
		sendHeader("Allow", allowedMethods);
		endHeader();
		writeClientBody(body);
		endResponse();
	}

	/**
	 * Tells client to look somewhere else for the resource they requested
	 */
	public void respondRedirect(String location) throws IOException {
		HttpStatus status = HttpStatus.MOVED_PERMANENTLY;
		String body = Utils.getSimpleHtmlMessage(status.getFullName(), status.description);
		beginResponse(status);
		sendHeader("Content-Length", String.valueOf(body.length()));
		sendHeader("Content-Type", "text/html");
		sendHeader("Location", location);
		endHeader();
		writeClientBody(body);
		endResponse();
	}

	public void writeClientBody(String body) throws IOException {
		if (!method.equalsIgnoreCase("HEAD")) clientOut.writeBytes(body);
	}
	
	public void writeClientBody(byte[] body) throws IOException {
		if (!method.equalsIgnoreCase("HEAD")) clientOut.write(body);
	}

	public void writeRemoteBody(String body) throws IOException {
		remoteOut.writeBytes(body);
	}
	
	public void writeRemoteBody(byte[] body) throws IOException {
		remoteOut.write(body);
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
		message = id + "\t| " + message;
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
