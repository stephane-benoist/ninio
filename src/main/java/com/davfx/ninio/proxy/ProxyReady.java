package com.davfx.ninio.proxy;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.common.Address;
import com.davfx.ninio.common.FailableCloseableByteBufferHandler;
import com.davfx.ninio.common.Queue;
import com.davfx.ninio.common.QueueReady;
import com.davfx.ninio.common.Ready;
import com.davfx.ninio.common.ReadyConnection;
import com.davfx.util.ConfigUtils;
import com.davfx.util.Pair;
import com.typesafe.config.Config;

final class ProxyReady {
	private static final Logger LOGGER = LoggerFactory.getLogger(ProxyReady.class);
	private static final Config CONFIG = ConfigUtils.load(ProxyReady.class);

	public static final double DEFAULT_CONNECTION_TIMEOUT = ConfigUtils.getDuration(CONFIG, "proxy.timeout.connection");
	//%% public static final double DEFAULT_READ_TIMEOUT = ConfigUtils.getDuration(CONFIG, "proxy.timeout.read");

	private final Address proxyServerAddress;

	private final Object lock = new Object();
	private DataOutputStream currentOut = null;
	private Map<Integer, Pair<Address, FailableCloseableByteBufferHandler>> currentEstablishedConnections;
	private int nextConnectionId;
	private double connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
	private double readTimeout = Double.NaN;
	
	private final ProxyUtils.ClientSide proxyUtils = ProxyUtils.client();
	
	private Executor executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, ProxyReady.class.getSimpleName());
		}
	});
	private ProxyListener listener = new ProxyListener() {
		@Override
		public void failed(IOException e) {
		}
		@Override
		public void disconnected() {
		}
		@Override
		public void connected() {
		}
	};
	
	public ProxyReady(Address proxyServerAddress) {
		this.proxyServerAddress = proxyServerAddress;
	}
	
	public ProxyReady withExecutor(Executor executor) {
		this.executor = executor;
		return this;
	}
	
	public ProxyReady withConnectionTimeout(double connectionTimeout) {
		this.connectionTimeout = connectionTimeout;
		return this;
	}
	public ProxyReady withReadTimeout(double readTimeout) {
		this.readTimeout = readTimeout;
		return this;
	}
	
	public ProxyReady listen(ProxyListener listener) {
		this.listener = listener;
		return this;
	}
	
	public ProxyReady override(String type, ProxyUtils.ClientSideConfigurator configurator) {
		proxyUtils.override(type, configurator);
		return this;
	}

	public Ready get(final Queue queue, final String connecterType) {
		return new QueueReady(queue, new Ready() {
			@Override
			public void connect(final Address address, final ReadyConnection connection) {
				queue.check();
				
				final DataOutputStream out;
				final Map<Integer, Pair<Address, FailableCloseableByteBufferHandler>> establishedConnections;
				int connectionId;
				
				synchronized (lock) {
					if (currentOut == null) {
						final DataInputStream in;
						final Socket socket;
			
						try {
							socket = new Socket();
							socket.connect(new InetSocketAddress(proxyServerAddress.getHost(), proxyServerAddress.getPort()), (int) (connectionTimeout * 1000d));
							if (!Double.isNaN(readTimeout)) {
								socket.setSoTimeout((int) (readTimeout * 1000d));
							}
							try {
								out = new DataOutputStream(socket.getOutputStream());
								in = new DataInputStream(socket.getInputStream());
							} catch (IOException ee) {
								try {
									socket.close();
								} catch (IOException se) {
								}
								throw ee;
							}
						} catch (final IOException ioe) {
							connection.failed(new IOException("Connection to proxy cannot be established", ioe));
							executor.execute(new Runnable() {
								@Override
								public void run() {
									listener.failed(ioe);
								}
							});
							return;
						}
			
						establishedConnections = new HashMap<>();
			
						executor.execute(new Runnable() {
							@Override
							public void run() {
								listener.connected();
	
								while (true) {
									try {
										LOGGER.trace("Client waiting for connection ID");
										final int connectionId = in.readInt();
										int len = in.readInt();
										if (len < 0) {
											int command = -len;
											if (command == ProxyCommons.COMMAND_ESTABLISH_CONNECTION) {
												establishedConnections.put(connectionId, new Pair<Address, FailableCloseableByteBufferHandler>(address, connection));
												connection.connected(new FailableCloseableByteBufferHandler() {
													@Override
													public void close() {
														synchronized (lock) {
															establishedConnections.remove(connectionId);
														}
														try {
															out.writeInt(connectionId);
															out.writeInt(0);
															out.flush();
														} catch (IOException ioe) {
															try {
																out.close();
															} catch (IOException e) {
															}
															LOGGER.trace("Connection lost", ioe);
														}
													}
													
													@Override
													public void failed(IOException e) {
														try {
															out.close();
														} catch (IOException ioe) {
														}
														LOGGER.warn("Connection cut", e);
													}
													
													@Override
													public void handle(Address address, ByteBuffer buffer) {
														if (!buffer.hasRemaining()) {
															return;
														}
														try {
															out.writeInt(connectionId);
															out.writeInt(buffer.remaining());
															out.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
															out.flush();
														} catch (IOException ioe) {
															try {
																out.close();
															} catch (IOException e) {
															}
															LOGGER.trace("Connection lost", ioe);
														}
													}
												});
											} else if (command == ProxyCommons.COMMAND_FAIL_CONNECTION) {
												connection.failed(new IOException("Failed"));
											}
										} else if (len == 0) {
											Pair<Address, FailableCloseableByteBufferHandler> currentConnection;
											synchronized (lock) {
												currentConnection = establishedConnections.remove(connectionId);
											}
											if (currentConnection != null) {
												currentConnection.second.close();
											}
										} else {
											Pair<Address, FailableCloseableByteBufferHandler> currentConnection;
											synchronized (lock) {
												currentConnection = establishedConnections.get(connectionId);
											}
											final byte[] b = new byte[len];
											in.readFully(b);
											if (currentConnection != null) {
												currentConnection.second.handle(address, ByteBuffer.wrap(b));
											}
										}
									} catch (IOException ioe) {
										LOGGER.warn("Connection lost with server", ioe);
										break;
									}
								}
			
								try {
									socket.close();
								} catch (IOException e) {
								}
			
								synchronized (lock) {
									currentOut = null;
									currentEstablishedConnections = null;
								}

								for (Pair<Address, FailableCloseableByteBufferHandler> c : establishedConnections.values()) {
									c.second.close();
								}
								
								listener.disconnected();
							}
						});
						
						currentOut = out;
						currentEstablishedConnections = establishedConnections;
						nextConnectionId = 0;
					} else {
						out = currentOut;
						establishedConnections = currentEstablishedConnections;
					}
					
					connectionId = nextConnectionId;
					nextConnectionId++;
				}
				
				try {
					out.writeInt(connectionId);
					out.writeInt(-ProxyCommons.COMMAND_ESTABLISH_CONNECTION);
					out.writeUTF(address.getHost());
					out.writeInt(address.getPort());
					proxyUtils.write(connecterType, out);
					out.flush();
				} catch (IOException ioe) {
					connection.failed(new IOException("Connection to proxy failed", ioe));
				}
			}
		});
	}
}
