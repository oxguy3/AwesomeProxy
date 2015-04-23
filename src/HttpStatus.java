
public enum HttpStatus {
	
	CONTINUE				(100, "Continue"),
	SWITCH_PROTOCOLS		(101, "Switching Protocols"),
	
	OK						(200, "OK"),
	CREATED					(201, "Created"),
	ACCEPTED				(202, "Accepted"),
	NON_AUTHORITATIVE		(203, "Non-Authoritative Information"),
	NO_CONTENT				(204, "No Content"),
	RESET_CONTENT			(205, "Reset Content"),
	PARTIAL_CONTENT			(206, "Partial Content"),
	
	MULTIPLE_CHOICES		(300, "Multiple Choices"),
	MOVED_PERMANENTLY		(301, "Moved Permanently",
			"The resource previously located at this address has been moved."),
	FOUND					(302, "Found"),
	SEE_OTHER				(303, "See Other"),
	NOT_MODIFIED			(304, "Not Modified"),
	USE_PROXY				(305, "Use Proxy"),
	TEMPORARY_REDIRECT		(307, "Temporary Redirect"),
	
	BAD_REQUEST				(400, "Bad Request",
			"You made an invalid request that the server cannot process."),
	UNAUTHORIZED			(401, "Unauthorized",
			"This URL cannot be accessed without prior authentication."),
	PAYMENT_REQUIRED		(402, "Payment Required"),
	FORBIDDEN				(403, "Forbidden",
			"You are not permitted to access this URI."),
	NOT_FOUND				(404, "Not Found",
			"The requested URL could not be found. Please check the address and try again."),
	METHOD_NOT_ALLOWED		(405, "Method Not Allowed",
			"The method you used is not supported by the URI you requested."),
	NOT_ACCEPTABLE			(406, "Not Acceptable"),
	PROXY_AUTH_REQUIRED		(407, "Proxy Authentication Required"),
	REQUEST_TIMEOUT			(408, "Request Timeout",
			"You took too long to finish sending your request."),
	CONFLICT				(409, "Conflict"),
	GONE					(410, "Gone"),
	LENGTH_REQUIRED			(411, "Length Required"),
	PRECONDITION_FAILED		(412, "Precondition Failed"),
	REQ_ENTITY_TOO_LARGE	(413, "Request Entity Too Large"),
	REQ_URI_TOO_LONG		(414, "Request-URI Too Long"),
	UNSUPPORTED_MEDIA_TYPE	(415, "Unsupported Media Type"),
	REQ_RANGE_NOT_SATIS		(416, "Requested Range Not Satisfiable"),
	EXPECTATION_FAILED		(417, "Expectation Failed"),
	IM_A_TEAPOT				(418, "I'm a teapot",
			"I'm a little teapot, short and stout. Here is my handle, here is my spout."),
	
	INTERNAL_SERVER_ERROR	(500, "Internal Server Error",
			"An unknown error occurred."),
	NOT_IMPLEMENTED			(501, "Not Implemented",
			"This server only supports GET, HEAD, and POST requests."),
	BAD_GATEWAY				(502, "Bad Gateway",
			"The remote server failed to return a valid response."),
	SERVICE_UNAVAILABLE		(503, "Service Unavailable",
			"The service you attempted to use is temporarily inactive."),
	GATEWAY_TIMEOUT			(504, "Gateway Timeout",
			"The remote server failed to respond quickly enough."),
	HTTP_VER_NOT_SUPPORTED 	(505, "HTTP Version Not Supported",
			"This server only supports HTTP 1.1 requests.");

	public final int code;
	public final String name;
	public final String description;
	
	HttpStatus(int code, String name) {
		this(code, name, "See <a href=\"http://httpstatus.es/" 
				+ code + "\">this page</a> for more information about this status message.");
    }
	
	HttpStatus(int code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }
	
	public String getFullName() {
		return code + " " + name;
	}
}
