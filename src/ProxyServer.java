import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Locale;


public class ProxyServer extends Thread {
	
	final static String SERVER_NAME = "Best damn proxy server";
	final static String HTTP_VERSION = "HTTP/1.1";
	final static String CRLF = "\r\n";
	final static String LF = "\n";
	
	volatile static boolean stayAlive;
	
	Socket server;
	BufferedReader buff;
	DataOutputStream dos;
	Socket client;
	DataInputStream remoteDis;
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
		
		try {
			ServerSocket srvSock = new ServerSocket(port);
			
			System.out.println("Listening for connections on port " + Integer.toString(port) + "...");
			
			stayAlive = true;
			
			while (stayAlive) {
				Socket server = srvSock.accept();
				if (!stayAlive) break;

				System.out.println("Received new connection...");
				ProxyServer worker = new ProxyServer(server);
				worker.start();
			}
			
			//srvSock.close();
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void run() {
		try {
			//server.getInputStream().skip(server.getInputStream().available());
			buff = new BufferedReader(new InputStreamReader(server.getInputStream(), Charset.forName("UTF-8")));
			dos = new DataOutputStream(server.getOutputStream());
			
			
			ArrayList<String> lines = new ArrayList<String>();
			
			String line;
			while ((line = buff.readLine()) != null) {
				
				// break after two lines
				if (line.equals("")) break;
				
				System.out.println("C>P: "+line);
				lines.add(line);
			}
			
			if (lines.size() < 1) {
				System.out.println("Closing empty request...");
				server.close();
				return;
			}
			
			String request = lines.get(0);
			
			String[] requestArgs = request.split(" ");
			if (requestArgs.length != 3) {
				respondNoContent(HttpStatus.BAD_REQUEST);
				return;
			}
			if (!requestArgs[0].equals("GET")) {
				respondNoContent(HttpStatus.METHOD_NOT_ALLOWED);
				return;
			}
			if (!requestArgs[2].equals(HTTP_VERSION)) {
				respondNoContent(HttpStatus.HTTP_VER_NOT_SUPPORTED);
				return;
			}
			
			String requestUrl = requestArgs[1];
			if (requestUrl.equals("/exit")) {
				ProxyServer.stayAlive = false;
				respondWithBody(HttpStatus.OK, "<html><body><h1>OK</h1><p>Server will stop accepting new requests...</p></body></html>");
				return;
			}
			if (!requestUrl.startsWith("http://")) {
				respondNoContent(HttpStatus.FORBIDDEN);
				return;
			}
			String urlMinusProtocol = requestUrl.substring("http://".length());
			String hostname = urlMinusProtocol.split("/")[0];
			String requestPath = urlMinusProtocol.substring(hostname.length());
			
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
			
			String remoteLine;
			while ((remoteLine = remoteDis.readLine()) != null) {
				
				// break after two lines
				if (remoteLine.equals("")) break;
				
				String[] splitLine = remoteLine.split(":");
				if (splitLine.length < 2) {
					respondNoContent(HttpStatus.BAD_GATEWAY);
					return;
				}
				String headerKey = splitLine[0].toLowerCase();
				remoteHeaders.put(headerKey, remoteLine.substring(headerKey.length()+1).trim());
			}
			
			
			String contentLengthStr = remoteHeaders.get("content-length");
			if (contentLengthStr == null) {
				respondNoContent(HttpStatus.BAD_GATEWAY);
				return;
			}
			int contentLength = Integer.parseInt(contentLengthStr);
			
			byte[] remoteBody = new byte[contentLength];
			for (int i = 0; i < contentLength; i++) {
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
		dos.writeBytes(HTTP_VERSION + " " + status.code + " " + status.name);
		sendHeader("Server", SERVER_NAME);
		sendHeader("Date", new SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Locale.US).format(new Date()));
	}
	
	public void endResponse() throws IOException {
		dos.close();
		if (server != null && !server.isClosed()) server.close();
		if (client != null && !client.isClosed()) client.close();
	}
	
	public void respondWithBody(HttpStatus status, String body) throws IOException {
		beginResponse(status);
		dos.writeBytes(CRLF+CRLF+body);
		endResponse();
	}
	
	public void respondNoContent(HttpStatus status) throws IOException {
		beginResponse(status);
		endResponse();
	}
	
	public void sendHeader(String key, String value) throws IOException {
		dos.writeBytes(CRLF + key + ": " + value);
	}

}
