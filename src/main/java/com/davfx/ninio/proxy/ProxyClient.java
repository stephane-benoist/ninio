package com.davfx.ninio.proxy;

import java.util.concurrent.Executor;

import com.davfx.ninio.common.Address;
import com.davfx.ninio.common.ReadyFactory;

public final class ProxyClient {
	private final ProxyReady proxyReady;

	public ProxyClient(Address proxyServerAddress) {
		proxyReady = new ProxyReady(proxyServerAddress);
	}

	public ReadyFactory socket() {
		return new ProxyReadyFactory(proxyReady, ProxyUtils.SOCKET_TYPE);
	}
	public ReadyFactory datagram() {
		return new ProxyReadyFactory(proxyReady, ProxyUtils.DATAGRAM_TYPE);
	}
	public ReadyFactory ping() {
		return new ProxyReadyFactory(proxyReady, ProxyUtils.PING_TYPE);
	}
	public ReadyFactory reproxy() {
		return new ProxyReadyFactory(proxyReady, ProxyUtils.REPROXY_TYPE);
	}

	public ProxyClient withExecutor(Executor executor) {
		proxyReady.withExecutor(executor);
		return this;
	}
	public ProxyClient listen(ProxyListener listener) {
		proxyReady.listen(listener);
		return this;
	}
	public ProxyClient withConnectionTimeout(double connectionTimeout) {
		proxyReady.withConnectionTimeout(connectionTimeout);
		return this;
	}
	public ProxyClient withReadTimeout(double readTimeout) {
		proxyReady.withReadTimeout(readTimeout);
		return this;
	}
	public ProxyClient override(String type, ProxyUtils.ClientSideConfigurator configurator) {
		proxyReady.override(type, configurator);
		return this;
	}
	public ReadyFactory of(String type) {
		return new ProxyReadyFactory(proxyReady, type);
	}
	
	public ProxyClient reproxy(Address address) {
		override(ProxyUtils.REPROXY_TYPE, Reproxy.client(address, ProxyUtils.SOCKET_TYPE));
		return this;
	}
}
