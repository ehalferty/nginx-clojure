package nginx.clojure;

import static nginx.clojure.MiniConstants.BYTE_ARRAY_OFFSET;
import static nginx.clojure.MiniConstants.CONTENT_TYPE;
import static nginx.clojure.MiniConstants.DEFAULT_ENCODING;
import static nginx.clojure.MiniConstants.KNOWN_RESP_HEADERS;
import static nginx.clojure.MiniConstants.NGX_DONE;
import static nginx.clojure.MiniConstants.NGX_HTTP_CLOJURE_HEADERSO_CONTENT_TYPE_LEN_OFFSET;
import static nginx.clojure.MiniConstants.NGX_HTTP_CLOJURE_HEADERSO_CONTENT_TYPE_OFFSET;
import static nginx.clojure.MiniConstants.NGX_HTTP_CLOJURE_HEADERSO_HEADERS_OFFSET;
import static nginx.clojure.MiniConstants.NGX_HTTP_CLOJURE_HEADERSO_STATUS_OFFSET;
import static nginx.clojure.MiniConstants.NGX_HTTP_CLOJURE_REQ_HEADERS_OUT_OFFSET;
import static nginx.clojure.MiniConstants.NGX_HTTP_CLOJURE_REQ_POOL_OFFSET;
import static nginx.clojure.MiniConstants.NGX_HTTP_HEADER_FILTER_PHASE;
import static nginx.clojure.MiniConstants.NGX_HTTP_INTERNAL_SERVER_ERROR;
import static nginx.clojure.MiniConstants.NGX_HTTP_NO_CONTENT;
import static nginx.clojure.MiniConstants.NGX_HTTP_OK;
import static nginx.clojure.MiniConstants.NGX_HTTP_SWITCHING_PROTOCOLS;
import static nginx.clojure.MiniConstants.STRING_CHAR_ARRAY_OFFSET;
import static nginx.clojure.NginxClojureRT.UNSAFE;
import static nginx.clojure.NginxClojureRT.coroutineEnabled;
import static nginx.clojure.NginxClojureRT.handleResponse;
import static nginx.clojure.NginxClojureRT.log;
import static nginx.clojure.NginxClojureRT.ngx_http_clojure_mem_build_file_chain;
import static nginx.clojure.NginxClojureRT.ngx_http_clojure_mem_build_temp_chain;
import static nginx.clojure.NginxClojureRT.ngx_http_clojure_mem_inc_req_count;
import static nginx.clojure.NginxClojureRT.ngx_http_set_content_type;
import static nginx.clojure.NginxClojureRT.pickByteBuffer;
import static nginx.clojure.NginxClojureRT.pushNGXInt;
import static nginx.clojure.NginxClojureRT.pushNGXSizet;
import static nginx.clojure.NginxClojureRT.pushNGXString;
import static nginx.clojure.NginxClojureRT.workers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import nginx.clojure.NginxClojureRT.WorkerResponseContext;
import nginx.clojure.java.Constants;
import nginx.clojure.java.NginxJavaResponse;
import sun.nio.cs.ThreadLocalCoders;


public abstract class NginxSimpleHandler implements NginxHandler {

	public abstract NginxRequest makeRequest(long r, long c);
	
	@Override
	public int execute(final long r, final long c) {
		
		if (r == 0) { //by worker init process
			NginxResponse resp = handleRequest(makeRequest(0, 0));
			if (resp != null && resp.type() == NginxResponse.TYPE_FAKE_ASYNC_TAG && resp.fetchStatus(200) != 200) {
				log.error("initialize error %s", resp);
				return NGX_HTTP_INTERNAL_SERVER_ERROR;
			}
			return NGX_HTTP_OK;
		}
		
		final NginxRequest req = makeRequest(r, c);
		int phase = req.phase();
		
		if (workers == null) {
			NginxResponse resp = handleRequest(req);
			if (resp.type() == NginxResponse.TYPE_FAKE_ASYNC_TAG) {
				if (!req.isReleased() && !req.isHijacked() && (phase == -1 || phase == NGX_HTTP_HEADER_FILTER_PHASE)) { //from content handler invoking 
					ngx_http_clojure_mem_inc_req_count(r);
				}
				return NGX_DONE;
			}
			return handleResponse(req, resp);
		}
		
		//for safe access with another thread
		req.prefetchAll();

		if (phase == -1 || phase == NGX_HTTP_HEADER_FILTER_PHASE) { // -1 means from content handler invoking 
			ngx_http_clojure_mem_inc_req_count(r);
		}
		workers.submit(new Callable<NginxClojureRT.WorkerResponseContext>() {
			@Override
			public WorkerResponseContext call() throws Exception {
				NginxResponse resp = handleRequest(req);
				//let output chain built before entering the main thread
				return new WorkerResponseContext(resp, req);
			}
		});

		return NGX_DONE;
	}
	

	
	public static NginxResponse handleRequest(final NginxRequest req) {
		try{
			
			if (coroutineEnabled) {
				CoroutineRunner coroutineRunner = new CoroutineRunner(req);
				Coroutine coroutine = new Coroutine(coroutineRunner);
				coroutine.resume();
				if (coroutine.getState() == Coroutine.State.FINISHED) {
					return coroutineRunner.response;
				}else {
					return new NginxJavaResponse(req, Constants.ASYNC_TAG);
				}
			}else {
				return req.handler().process(req);
			}
		}catch(Throwable e){
			log.error("server unhandled exception!", e);
			return buildUnhandledExceptionResponse(req, e);
		}
	}
	
	public static interface SimpleEntrySetter {
		public  Object setValue(Object value);
	}
	
	public final static SimpleEntrySetter readOnlyEntrySetter = new SimpleEntrySetter() {
		public Object setValue(Object value) {
			throw new UnsupportedOperationException("read only entry can not set!");
		}
	};
	
	public static class SimpleEntry<K, V> implements Entry<K, V> {

		public K key;
		public V value;
		public SimpleEntrySetter setter;
		
		public SimpleEntry(K key, V value, SimpleEntrySetter simpleEntrySetter) {
			this.key = key;
			this.value = value;
			this.setter = simpleEntrySetter;
		}
		
		@Override
		public K getKey() {
			return key;
		}

		@Override
		public V getValue() {
			return value;
		}

		@Override
		public V setValue(V value) {
			return (V)setter.setValue(value);
		}
	}
	
	
	public static class NginxUnhandledExceptionResponse extends NginxSimpleResponse {
		
		Throwable err;
		NginxRequest r;
		
		public NginxUnhandledExceptionResponse(NginxRequest r, Throwable e) {
			this.err = e;
			this.r = r;
			if (r.isReleased()) {
				this.type = TYPE_FATAL;
			}else {
				this.type = TYPE_ERROR;
			}
		}
		
		@Override
		public int fetchStatus(int defaultStatus) {
			return 500;
		}
		
		@Override
		public <K, V> Collection<Entry<K, V>> fetchHeaders() {
			return (List)Arrays.asList(new SimpleEntry(CONTENT_TYPE, "text/plain", readOnlyEntrySetter));
		}
		@Override
		public Object fetchBody() {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			err.printStackTrace(pw);
			pw.close();
			return sw.toString();
		}
		@Override
		public NginxRequest request() {
			return r;
		}
	}

	public static NginxResponse buildUnhandledExceptionResponse(NginxRequest r, Throwable e) {
		return new NginxUnhandledExceptionResponse(r, e);
	}

	
	public static final class CoroutineRunner implements Runnable {
		
		final NginxRequest request;
		NginxResponse response;
		
		
		public CoroutineRunner(NginxRequest request) {
			super();
			this.request = request;
		}

		@SuppressWarnings("rawtypes")
		@Override
		public void run() throws SuspendExecution {
			try {
				response = request.handler().process(request);
			}catch(Throwable e) {
				response = buildUnhandledExceptionResponse(request, e);
				log.error("unhandled exception in coroutine", e);
			}
			
			if (Coroutine.getActiveCoroutine().getResumeCounter() != 1) {
				request.handler().completeAsyncResponse(request, response);
			}
		}
	}
	
	@Override
	public NginxHeaderHolder fetchResponseHeaderPusher(String name) {
		NginxHeaderHolder pusher = KNOWN_RESP_HEADERS.get(name);
		if (pusher == null) {
			pusher = new UnknownHeaderHolder(name, NGX_HTTP_CLOJURE_HEADERSO_HEADERS_OFFSET);
		}
		return pusher;
	}

	@Override
	public <K, V> long prepareHeaders(NginxRequest req, long status, Collection<Map.Entry<K, V>> headers) {
		long r = req.nativeRequest();
		long pool = UNSAFE.getAddress(r + NGX_HTTP_CLOJURE_REQ_POOL_OFFSET);
		long headers_out = r + NGX_HTTP_CLOJURE_REQ_HEADERS_OUT_OFFSET;
		
		String contentType = null;
		String server = null;
		if (headers != null) {
			for (Map.Entry<?, ?> hen : headers) {
				Object nameObj = hen.getKey();
				Object val = hen.getValue();
				
				if (nameObj == null || val == null) {
					continue;
				}
				
				String name = normalizeHeaderName(nameObj);
				if (name == null || name.length() == 0) {
					continue;
				}
				
				NginxHeaderHolder pusher = fetchResponseHeaderPusher(name);
				if (pusher == KNOWN_RESP_HEADERS.get("Content-Type")) {
					if (val instanceof String) {
						contentType = (String)val;
					}else {
						
					}
				}
				pusher.push(headers_out, pool, val);
			}
		}
		
		if (contentType == null && status != NGX_HTTP_SWITCHING_PROTOCOLS){
			ngx_http_set_content_type(r);
		}else {
			int contentTypeLen = pushNGXString(headers_out + NGX_HTTP_CLOJURE_HEADERSO_CONTENT_TYPE_OFFSET, contentType, DEFAULT_ENCODING, pool);
			//be friendly to gzip module 
			pushNGXSizet(headers_out + NGX_HTTP_CLOJURE_HEADERSO_CONTENT_TYPE_LEN_OFFSET, contentTypeLen);
		}
		
		pushNGXInt(headers_out + NGX_HTTP_CLOJURE_HEADERSO_STATUS_OFFSET, (int)status);
		return r;
	}

	
	@Override
	public long buildOutputChain(NginxResponse response) {
		long r = response.request().nativeRequest();
		try {
			long pool = UNSAFE.getAddress(r + NGX_HTTP_CLOJURE_REQ_POOL_OFFSET);
			int status = response.fetchStatus(NGX_HTTP_OK);

			
			Object body = response.fetchBody();
			long chain = 0;
			
			if (body != null) {
				chain = buildResponseItemBuf(r, body, chain);
				if (chain == 0) {
					return -NGX_HTTP_INTERNAL_SERVER_ERROR;
				}else if (chain < 0 && chain != -204) {
					return chain;
				}
			}else {
				chain = -NGX_HTTP_NO_CONTENT;
			}
			
			if (chain == -NGX_HTTP_NO_CONTENT) {
				if (status == NGX_HTTP_OK) {
					status = NGX_HTTP_NO_CONTENT;
				}
				return -status;
			}
			
			return chain;

		}catch(Throwable e) {
			log.error("server unhandled exception!", e);
			return -NGX_HTTP_INTERNAL_SERVER_ERROR;
		}
	}
	
	protected  long buildResponseFileBuf(File f, long r, long chain) {
		ByteBuffer b = HackUtils.encode(f.getPath(), DEFAULT_ENCODING, pickByteBuffer());
		if (b.remaining() < b.capacity()) {
			b.array()[b.remaining()] = 0; // for file name in c language is ended with '\0'
		}
		chain = ngx_http_clojure_mem_build_file_chain(r, chain, b.array(), BYTE_ARRAY_OFFSET, b.remaining());
		if (chain <= 0) {
			return chain;
		}
		return chain;
	}
	
	//TODO: optimize handling inputstream with large lazy data
	protected  long buildResponseInputStreamBuf(InputStream in, long r,  final long preChain) {
		try {
			long chain = preChain;
			long first = 0;
			byte[] buf = pickByteBuffer().array();
			while (true) {
				int c = 0;
				int pos = 0;
				do {
					c = in.read(buf, pos, buf.length - pos);
					if (c > 0) {
						pos += c;
					}
				}while (c >= 0 && pos < buf.length);
				
				if (pos > 0) {
					chain = ngx_http_clojure_mem_build_temp_chain(r, chain, buf, BYTE_ARRAY_OFFSET, pos);
					if (chain <= 0) {
						return chain;
					}
					if (first == 0) {
						first = chain;
					}
				}
				
				if (c < 0) {
					break;
				}
			}
			
			return preChain == 0 ? (first == 0 ? -NGX_HTTP_NO_CONTENT : first)  : chain;
		}catch(IOException e) {
			log.error("can not read from InputStream", e);
			return -500; 
		}finally {
			try {
				in.close();
			} catch (IOException e) {
				log.error("can not close  InputStream", e);
			}
		}
	}
	
	protected long buildResponseStringBuf(String s, long r,  final long preChain) {
		if (s == null) {
			return 0;
		}

		if (s.length() == 0) {
			return -NGX_HTTP_NO_CONTENT;
		}

		CharsetEncoder charsetEncoder = ThreadLocalCoders.encoderFor(DEFAULT_ENCODING)
				.onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
		ByteBuffer bb = pickByteBuffer();
		CharBuffer cb = CharBuffer.wrap((char[]) UNSAFE.getObject(s, STRING_CHAR_ARRAY_OFFSET));
		charsetEncoder.reset();
		CoderResult result = CoderResult.UNDERFLOW;
		long first = 0;
		long chain = preChain;
		do {
			result = charsetEncoder.encode(cb, bb, true);
			if (result == CoderResult.OVERFLOW) {
				bb.flip();
				chain = ngx_http_clojure_mem_build_temp_chain(r, chain, bb.array(), BYTE_ARRAY_OFFSET, bb.remaining());
				if (chain <= 0) {
					return chain;
				}
				bb.clear();
				if (first == 0) {
					first = chain;
				}
			} else if (result == CoderResult.UNDERFLOW) {
				break;
			} else {
				log.error("%s can not decode string : %s", result.toString(), s);
				return -NGX_HTTP_INTERNAL_SERVER_ERROR;
			}
		} while (true);

		while (charsetEncoder.flush(bb) == CoderResult.OVERFLOW) {
			bb.flip();
			chain = ngx_http_clojure_mem_build_temp_chain(r, chain, bb.array(), BYTE_ARRAY_OFFSET, bb.remaining());
			if (chain <= 0) {
				return chain;
			}
			if (first == 0) {
				first = chain;
			}
			bb.clear();
		}

		if (bb.hasRemaining()) {
			bb.flip();
			chain = ngx_http_clojure_mem_build_temp_chain(r, chain, bb.array(), BYTE_ARRAY_OFFSET, bb.remaining());
			if (chain <= 0) {
				return chain;
			}
			if (first == 0) {
				first = chain;
			}
			bb.clear();
		}

		return preChain == 0 ? first : chain ;
	}
	
	protected  long buildResponseIterableBuf(Iterable iterable, long r,  long preChain) {
		if (iterable == null) {
			return 0;
		}
		
		Iterator i = iterable.iterator();
		if (!i.hasNext()) {
			return -204;
		}

		long chain = preChain;
		long first = 0;
		while (i.hasNext()) {
			Object o = i.next();
			if (o != null) {
				long rc  = buildResponseItemBuf(r, o, chain);
				if (rc <= 0) {
					if (rc != -NGX_HTTP_NO_CONTENT) {
						return rc;
					}
				}else {
					chain = rc;
					if (first == 0) {
						first = chain;
					}
				}
			}
		}
		return preChain == 0 ? (first == 0 ? -NGX_HTTP_NO_CONTENT : first)  : chain;
	}
	
	
	protected  long buildResponseItemBuf(long r, Object item, long chain) {

		if (item instanceof File) {
			return buildResponseFileBuf((File)item, r, chain);
		}else if (item instanceof InputStream) {
			return buildResponseInputStreamBuf((InputStream)item, r, chain);
		}else if (item instanceof String) {
			return buildResponseStringBuf((String)item, r, chain);
		} 
		return buildResponseComplexItemBuf(r, item, chain);
	}
	
	protected long buildResponseComplexItemBuf(long r, Object item, long chain) {
		if (item == null) {
			return 0;
		}
		if (item instanceof Iterable) {
			return buildResponseIterableBuf((Iterable)item, r, chain);
		}else if (item.getClass().isArray()) {
			return buildResponseIterableBuf(Arrays.asList((Object[])item), r, chain);
		}
		return -NGX_HTTP_INTERNAL_SERVER_ERROR;
	}
	
	protected  String normalizeHeaderName(Object nameObj) {
		return normalizeHeaderNameHelper(nameObj);
	}

	public static String normalizeHeaderNameHelper(Object nameObj) {
		return nameObj instanceof String ? (String)nameObj : nameObj.toString();
	}
}
