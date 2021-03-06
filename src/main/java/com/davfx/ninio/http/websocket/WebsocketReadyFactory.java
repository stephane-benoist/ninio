package com.davfx.ninio.http.websocket;

import com.davfx.ninio.common.Queue;
import com.davfx.ninio.common.Ready;
import com.davfx.ninio.common.ReadyFactory;
import com.davfx.ninio.http.HttpClient;

public final class WebsocketReadyFactory implements ReadyFactory {
	private final HttpClient httpClient;

	public WebsocketReadyFactory(HttpClient httpClient) {
		this.httpClient = httpClient;
	}
	
	@Override
	public Ready create(Queue queue) {
		return new WebsocketReady(httpClient);
	}
}
