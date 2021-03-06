package com.davfx.ninio.http.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.common.Address;
import com.davfx.ninio.common.CloseableByteBufferHandler;
import com.davfx.ninio.common.Queue;
import com.davfx.ninio.common.Trust;
import com.davfx.ninio.http.HttpClient;
import com.davfx.ninio.http.HttpClientConfigurator;
import com.davfx.ninio.http.HttpClientHandler;
import com.davfx.ninio.http.HttpRequest;
import com.davfx.ninio.http.HttpResponse;
import com.davfx.ninio.http.HttpServer;
import com.davfx.ninio.http.HttpServerConfigurator;
import com.davfx.ninio.http.HttpServerHandler;
import com.davfx.ninio.http.HttpServerHandlerFactory;
import com.davfx.util.ConfigUtils;
import com.davfx.util.Pair;
import com.typesafe.config.Config;

public final class HeadersRouteHttpServer {
	private static final Logger LOGGER = LoggerFactory.getLogger(HeadersRouteHttpServer.class);
	
	private static final Config CONFIG = ConfigUtils.load(HeadersRouteHttpServer.class);

	public static void main(String[] args) throws Exception {
		Queue queue = new Queue();
		HeadersRouteHttpServer server = new HeadersRouteHttpServer(
			new HttpServerConfigurator(queue).withAddress(new Address(CONFIG.getString("http.route.bind.host"), CONFIG.getInt("http.route.bind.port"))),
			new HttpClientConfigurator(queue).withTrust(new Trust())
		);
		for (Config c : CONFIG.getConfigList("http.route.map")) {
			RouteFilterBuilder f = new RouteFilterBuilder(new Address(c.getString("to.host"), c.getInt("to.port")), c.getString("to.path"));
			for (Config cc : c.getConfigList("match")) {
				f.add(cc.getString("header"), Pattern.compile(cc.getString("pattern")));
			}
			server.route(f.finish());
		}
		server.start();
	}
	
	private static interface RouteFilter {
		Pair<Address, String> check(Map<String, String> headers);
	}
	
	private static final class RouteFilterBuilder {
		final List<Pair<String, Pattern>> headerMatches = new LinkedList<>();
		final Pair<Address, String> to;
		public RouteFilterBuilder(Address toAddress, String toPath) {
			to = new Pair<Address, String>(toAddress, toPath);
		}
		
		public RouteFilterBuilder add(String header, Pattern pattern) {
			headerMatches.add(new Pair<String, Pattern>(header, pattern));
			return this;
		}
		
		public RouteFilter finish() {
			return new RouteFilter() {
				@Override
				public Pair<Address, String> check(Map<String, String> headers) {
					for (Pair<String, Pattern> p : headerMatches) {
						String value = headers.get(p.first);
						if ((value == null) || !p.second.matcher(value).matches()) {
							return null;
						}
					}
					return to;
				}
			};
		}
	}
	
	private final HttpServerConfigurator serverConfigurator;
	private final HttpClient client;
	private final List<RouteFilter> routing = new LinkedList<>();
	
	public HeadersRouteHttpServer(HttpServerConfigurator serverConfigurator, HttpClientConfigurator clientConfigurator) {
		LOGGER.debug("Running router: {}", serverConfigurator.address);
		this.serverConfigurator = serverConfigurator;
		client = new HttpClient(clientConfigurator);
	}
	
	public void start() {
		new HttpServer(serverConfigurator, new HttpServerHandlerFactory() {
			@Override
			public HttpServerHandler create() {
				return new HttpServerHandler() {
					private CloseableByteBufferHandler clientWrite;
					private Write serverWrite;
					private final List<ByteBuffer> toWrite = new LinkedList<>();
					private boolean closed = false;
					
					@Override
					public void failed(IOException e) {
						close();
					}
					
					@Override
					public void close() {
						if (closed) {
							return;
						}
						closed = true;
						toWrite.clear();
						if (clientWrite != null) {
							clientWrite.close();
						}
					}
					
					@Override
					public void handle(Address address, ByteBuffer buffer) {
						if (closed) {
							return;
						}
						if (clientWrite != null) {
							clientWrite.handle(address, buffer);
						} else {
							toWrite.add(buffer);
						}
					}
					
					@Override
					public void ready(Write write) {
						if (closed) {
							write.failed(new IOException("No route defined"));
							return;
						}
						serverWrite = write;
					}
					
					@Override
					public void handle(HttpRequest request) {
						Pair<Address, String> to = null;
						for (RouteFilter filter : routing) {
							to = filter.check(request.getHeaders());
							if (to != null) {
								LOGGER.debug("Received matching request: {}, to: {}", request.getPath(), to);
								break;
							}
						}

						if (to == null) {
							closed = true;
							return;
						}

						LOGGER.debug("Routed to: {}", to);
						HttpRequest routedRequest = new HttpRequest(to.first, request.isSecure(), request.getMethod(), to.second + request.getPath(), request.getHeaders());
						//%% routedRequest.getHeaders().put(Http.HOST, to.toString());
						client.send(routedRequest, new HttpClientHandler() {
							@Override
							public void failed(IOException e) {
								serverWrite.failed(e);
							}
							
							@Override
							public void close() {
								serverWrite.close();
							}
							
							@Override
							public void handle(Address address, ByteBuffer buffer) {
								serverWrite.handle(address, buffer);
							}
							
							@Override
							public void received(HttpResponse response) {
								serverWrite.write(response);
							}
							
							@Override
							public void ready(CloseableByteBufferHandler write) {
								clientWrite = write;
								for (ByteBuffer b : toWrite) {
									clientWrite.handle(null, b);
								}
								toWrite.clear();
							}
						});
					}
				};
			}
			
			@Override
			public void closed() {
				LOGGER.debug("Server closed");
			}

			@Override
			public void failed(IOException e) {
				LOGGER.error("Server could not be launched", e);
			}
		});
	}
	
	public HeadersRouteHttpServer route(RouteFilter filter) {
		routing.add(filter);
		return this;
	}
}
