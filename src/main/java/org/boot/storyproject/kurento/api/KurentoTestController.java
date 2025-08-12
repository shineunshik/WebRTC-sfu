package org.boot.storyproject.kurento.api;

import lombok.RequiredArgsConstructor;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class KurentoTestController {

  private final KurentoClient kurentoClient;

  @GetMapping("/test/kurento")
  public String testKurento() {

    try {
      MediaPipeline pipeline = kurentoClient.createMediaPipeline();
      String pipelineId = pipeline.getId();
      pipeline.release(); // 테스트 후 정리

      return "Kurento 연결 성공! Pipeline ID: " + pipelineId;
    } catch (Exception e) {
      return "Kurento 연결 실패: " + e.getMessage();
    }

  }
}
