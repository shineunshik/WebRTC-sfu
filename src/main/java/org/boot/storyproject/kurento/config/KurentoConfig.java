package org.boot.storyproject.kurento.config;

import org.kurento.client.KurentoClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KurentoConfig {

  @Bean
  public KurentoClient kurentoClient() {
    return KurentoClient.create("ws://172.31.98.199:8888/kurento");
  }

}
