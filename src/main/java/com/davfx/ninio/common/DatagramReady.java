package com.davfx.ninio.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.LinkedList;

public final class DatagramReady implements Ready {
	private static final class AddressedByteBuffer {
		Address address;
		ByteBuffer buffer;
	}

	private final Selector selector;
	private final ByteBufferAllocator byteBufferAllocator;

	public DatagramReady(Selector selector, ByteBufferAllocator byteBufferAllocator) {
		this.selector = selector;
		this.byteBufferAllocator = byteBufferAllocator;
	}
	
	@Override
	public void connect(Address address, final ReadyConnection connection) {
		try {
			final DatagramChannel channel = DatagramChannel.open();
			try {
				channel.configureBlocking(false);
				final SelectionKey selectionKey = channel.register(selector, 0);
				
				final LinkedList<AddressedByteBuffer> toWriteQueue = new LinkedList<AddressedByteBuffer>();
	
				selectionKey.attach(new SelectionKeyVisitor() {
					@Override
					public void visit(final SelectionKey key) {
						if (!channel.isOpen()) {
							return;
						}
						if (key.isReadable()) {
							ByteBuffer readBuffer = byteBufferAllocator.allocate();
							try {
								InetSocketAddress from = (InetSocketAddress) channel.receive(readBuffer);
								if (from == null) {
									try {
										channel.close();
									} catch (IOException ee) {
									}
									connection.close();
								} else {
									readBuffer.flip();
									connection.handle(new Address(from.getHostName(), from.getPort()), readBuffer);
								}
							} catch (IOException e) {
								try {
									channel.close();
								} catch (IOException ee) {
								}
								connection.close();
							}
						} else if (key.isWritable()) {
							while (!toWriteQueue.isEmpty()) {
								AddressedByteBuffer b = toWriteQueue.getFirst();
								if (b == null) {
									try {
										channel.close();
									} catch (IOException ee) {
									}
									return;
								} else {
									try {
										if (b.address == null) {
											channel.write(b.buffer);
										} else {
											channel.send(b.buffer, AddressUtils.toConnectableInetSocketAddress(b.address));
										}
									} catch (IOException e) {
										try {
											channel.close();
										} catch (IOException ee) {
										}
										connection.close();
										break;
									}
									
									if (b.buffer.hasRemaining()) {
										return;
									}
									
									toWriteQueue.removeFirst();
								}
							}
							if (!selector.isOpen()) {
								return;
							}
							if (!channel.isOpen()) {
								return;
							}
							if (!selectionKey.isValid()) {
								return;
							}
							selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
						}
					}
				});
	
				selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
				
				try {
					boolean bind = false;
					InetSocketAddress a = AddressUtils.toConnectableInetSocketAddress(address);
					if (a == null) {
						a = AddressUtils.toBindableInetSocketAddress(address);
						bind = true;
					}
					if (a == null) {
						throw new IOException("Invalid address");
					}
					if (bind) {
						channel.socket().bind(a);
					} else {
						channel.connect(a);
					}
				} catch (IOException e) {
					selectionKey.cancel();
					throw e;
				}
	
				connection.connected(new FailableCloseableByteBufferHandler() {
					@Override
					public void handle(Address address, ByteBuffer buffer) {
						if (!selector.isOpen()) {
							return;
						}
						if (!channel.isOpen()) {
							return;
						}
						if (!selectionKey.isValid()) {
							return;
						}
						AddressedByteBuffer b = new AddressedByteBuffer();
						b.address = address;
						b.buffer = buffer;
						toWriteQueue.addLast(b);
						selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
					}
					@Override
					public void close() {
						if (!selector.isOpen()) {
							return;
						}
						if (!channel.isOpen()) {
							return;
						}
						if (!selectionKey.isValid()) {
							return;
						}
						toWriteQueue.addLast(null);
						selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
					}
					@Override
					public void failed(IOException e) {
						close();
					}
				});
			} catch (IOException e) {
				try {
					channel.close();
				} catch (IOException ee) {
				}
				throw e;
			}
		} catch (IOException e) {
			connection.failed(e);
		}
	}
}
