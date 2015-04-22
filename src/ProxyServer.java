import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Set;


public class ProxyServer extends Thread {
	
	final static String SERVER_NAME = "Best damn proxy server";
	final static String HTTP_VERSION = "HTTP/1.1";
	final static String BR = "\r\n";
	
	Socket server;
	
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
			
			while (true) {
				Socket server = srvSock.accept();

				System.out.println("Received new connection...");
				ProxyServer worker = new ProxyServer(server);
				worker.start();
			}
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void run() {
		try {
			//server.getInputStream().skip(server.getInputStream().available());
			BufferedReader buff = new BufferedReader(new InputStreamReader(server.getInputStream(), Charset.forName("UTF-8")));
			DataOutputStream dos = new DataOutputStream(server.getOutputStream());
			
			
			ArrayList<String> lines = new ArrayList<String>();
			
			String line;
			while ((line = buff.readLine()) != null) {
				
				// break after two lines
				if (line.equals("")) break;
				
				System.out.println(line);
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
				dos.writeBytes(HTTP_VERSION + " 400 Bad Request");
				sendObligatoryHeaders(dos);
				server.close();
				return;
			}
			if (!requestArgs[0].equals("GET")) {
				dos.writeBytes(HTTP_VERSION + " 405 Method Not Allowed");
				sendObligatoryHeaders(dos);
				server.close();
				return;
			}
			if (!requestArgs[2].equals(HTTP_VERSION)) {
				dos.writeBytes(HTTP_VERSION + " 505 HTTP Version Not Supported");
				sendObligatoryHeaders(dos);
				server.close();
				return;
			}
			
			String requestUrl = requestArgs[1];
			if (!requestUrl.startsWith("http://")) {
				dos.writeBytes(HTTP_VERSION + " 403 Forbidden");
				sendObligatoryHeaders(dos);
				server.close();
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
			
			Socket client = new Socket(hostname, remotePort);
			System.out.println("Connecting to remote server...");
			DataOutputStream remoteDos = new DataOutputStream(client.getOutputStream());
			DataInputStream remoteDis = new DataInputStream(client.getInputStream());
			//BufferedReader remoteBuff = new BufferedReader(new InputStreamReader(remoteIn));
			
			String remoteReq = "";
			
			remoteReq += "GET " + requestPath + " " + HTTP_VERSION + BR;
			remoteReq += "Host: " + hostname + BR;
			//sendObligatoryHeaders(remoteDos);
			remoteReq += BR;
			
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
					dos.writeBytes(HTTP_VERSION + " 502 Bad Gateway");
					sendObligatoryHeaders(dos);
					dos.close();
					server.close();
					client.close();
					return;
				}
				String headerKey = splitLine[0].toLowerCase();
				remoteHeaders.put(headerKey, remoteLine.substring(headerKey.length()+1).trim());
			}
			
			
			String contentLengthStr = remoteHeaders.get("content-length");
			if (contentLengthStr == null) {
				dos.writeBytes(HTTP_VERSION + " 502 Bad Gateway");
				sendObligatoryHeaders(dos);
				dos.close();
				server.close();
				client.close();
				return;
			}
			int contentLength = Integer.parseInt(contentLengthStr);
			
			byte[] remoteBody = new byte[contentLength];
			for (int i = 0; i < contentLength; i++) {
				remoteBody[i] = (byte) remoteDis.read();
			}
			
			System.out.println(Arrays.toString(remoteBody));
			
			
			System.out.println("Closing remote connection...");
			client.close();
			
			
			

			dos.writeBytes(remoteResponseLine);
			System.out.print("> "+remoteResponseLine);
			Enumeration<String> keys = remoteHeaders.keys();
			while (keys.hasMoreElements()) {
				String key = keys.nextElement();
				String header = "\n" + key + ": " + remoteHeaders.get(key);
				System.out.print(header+"> ");
				dos.writeBytes(header);
			}
			System.out.print("> "+"\n\n");
			dos.writeBytes("\n\n");

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
	
	public static void sendObligatoryHeaders(DataOutputStream dos) throws IOException {
		dos.writeBytes(BR + "Server: " + SERVER_NAME);
		dos.writeBytes(BR + "Date: " + new SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Locale.US).format(new Date()));
	}

}
