import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class Utils {
	
	/**
	 * INTERNAL SERVER CONFIGURATION
	 */
	// accept requests to internal server addresses?
	final static boolean ENABLE_INTERNAL_SERVER = true;
	
	// allow server actions (i.e. enable/disable proxy, shut down) via internal server?
	final static boolean ENABLE_INTERNAL_ACTIONS = true;
	
	// accept requests to static files on the internal server?
	// note: disabling this will make inaccessible the CSS files that make the web pages pretty
	final static boolean ENABLE_STATIC_FILES = true;
	
	// show directory listings for static files on the internal server?
	final static boolean ENABLE_DIRECTORY_INDEXING = true;
	
	// a relative or absolute path to root directory for static files
	final static String DOCUMENT_ROOT = "static";
	
	// when serving local static files, how many bytes of the file should be buffered at a time?
	final static int FILE_BUFFER_SIZE = 1024;
	
	
	/**
	 * LOGGING CONFIGURATION
	 */
	// should we log notices/errors?
	final static boolean LOG_MESSAGES = true;
	final static boolean LOG_ERRORS = true;
	
	// log RequestWorker messages/errors?
	final static boolean LOG_REQUEST_MESSAGES = true; // only if LOG_MESSAGES
	final static boolean LOG_REQUEST_ERRORS = false; // only if LOG_ERRORS
	final static boolean LOG_REQUEST_CLIENT_CONNECTIONS = true; // log requests w/client? (only if LOG_REQUEST_MESSAGES)
	final static boolean LOG_REQUEST_REMOTE_CONNECTIONS = true; // log requests w/remote? (only if LOG_REQUEST_MESSAGES)
	
	
	/**
	 * MISCELLANEOUS CONFIGURATION
	 */
	// name used for self-identification
	final static String SERVER_NAME = "AwesomeProxy";
	
	// The assignment said to only accept GET requests or return "Bad Method".
	// We also accept HEAD and POST, and we return the correct response of
	// "Not Implemented" for unsupported methods. To disable these
	// behaviors, set this boolean to true.
	final static boolean STICK_TO_THE_SCRIPT = false;
	
	
	
	
	
	
	// some strings that get used a lot, for convenience
	final static String HTTP_VERSION = "HTTP/1.1";
	final static String CRLF = "\r\n";
	final static String HTTP_HEADER_END = CRLF + CRLF;
	
	/**
	 * Returns the current datetime as an RFC 1123 formatted string
	 */
	public static String getRFC1123Date() {
		return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).format(new Date());
	}
	
	public static String getSimpleHtmlMessage(String title, String message) {
		
		return getSimpleHtml(title, "<div class=\"col-md-12\"><p>" + message + "</p></div>", "");
	}
	
	public static String getSimpleHtml(String title, String content, String css) {
		String body = "<!doctype html>\n<html>"
				+ "<head>"
				+ "<meta charset=\"utf-8\">"
				+ "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">"
				+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
				+ "<title>" + title + "</title>"
				+ "<link href=\"/css/bootstrap.min.css\" rel=\"stylesheet\">"
				+ "<style>"
				+ "footer { margin-top: 10px;}"
				+ css
				+ "</style>"
				+ "</head>"
				
				+ "<body>"
				+ "<div class=\"container\">"
				+ "<div class=\"row\">"
				
				+ "<div class=\"col-md-12\"><header class=\"page-header\"><h1>" + title + "</h1></header></div>"
				+ "<main>" + content + "</main>"
				
				+ "<div class=\"col-md-12\"><footer>"
				+ "<hr>"
				+ "<i>Page generated by <a href=\"/\">" + SERVER_NAME + "</a> on " + getRFC1123Date() + "</i>"
				+ "</footer></div>"

				+ "</div>"
				+ "</div>"

//				+ "<script src=\"/js/jquery.min.js\"></script>"
//				+ "<script src=\"/js/bootstrap.min.js\"></script>"
				
				+ "</body>"
				+ "</html>";
		
		return body;
	}
	
	/**
	 * Formats an HTTP header key-value pair
	 */
	public static String httpHeader(String key, String value) {
		return CRLF + key + ": " + value;
	}
	

	public static void log(String message) {
		if (LOG_MESSAGES) log(message, false);
	}
	
	public static void logError(String message) {
		if (LOG_ERRORS) log(message, true);
	}
	
	private static void log(String message, boolean isError) {
		(isError ? System.err : System.out).println(message);
	}
	
	/**
	 * Gets the most likely mimetype based on a file extension
	 * 
	 * @return mimetype, or null if not found
	 */
	public static String getMimetypeForExtension(String ext) {
		ext = ext.toLowerCase();
		switch(ext) {
		
		case "txt":
		case "text":
		case "log":
		case "h":
		case "c":
		case "c++":
		case "cc":
		case "f":
		case "jav":
		case "java":
			return "text/plain";
		case "css":
			return "text/css";
		case "js":
			return "text/javascript";
		case "htm":
		case "html":
			return "text/html";
		case "json":
			return "application/json";
		case "xml":
			return "application/xml";
		case "tar":
			return "application/x-tar";
		case "gz":
		case "gzip":
			return "application/x-gzip";
		case "zip":
			return "application/zip";
		case "doc":
			return "application/msword";
		case "pdf":
			return "application/pdf";
		case "png":
			return "image/png";
		case "jpg":
		case "jpeg":
		case "jpe":
		case "jfif":
			return "image/jpeg";
		case "gif":
			return "image/gif";
		case "tif":
		case "tiff":
			return "image/tiff";
		case "bmp":
			return "image/bmp";
		case "ico":
			return "image/x-icon";
		case "mp3":
			return "audio/mpeg3";
		case "mid":
		case "midi":
			return "audio/midi";
		case "wav":
			return "audio/wav";
		case "avi":
			return "video/avi";
		case "mov":
			return "video/quicktime";
		default:
			return null;
		}
	}
}
