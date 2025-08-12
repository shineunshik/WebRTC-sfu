package org.boot.storyproject.kurento.config;

import org.kurento.client.KurentoClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KurentoConfig {

  @Bean
  public KurentoClient kurentoClient() {
    return KurentoClient.create("ws://192.168.219.111:8888/kurento");
  }

}
