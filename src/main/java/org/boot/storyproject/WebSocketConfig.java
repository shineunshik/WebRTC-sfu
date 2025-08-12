package org.boot.storyproject;

import org.boot.storyproject.sfu.SfuHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    @Autowired
    private SfuHandler sfuHandler;
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 새로운 SFU 핸들러
        registry.addHandler(sfuHandler, "/sfu").setAllowedOrigins("*");
    }
}