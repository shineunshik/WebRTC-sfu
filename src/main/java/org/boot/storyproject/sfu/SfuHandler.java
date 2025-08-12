package org.boot.storyproject.sfu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.kurento.client.*;
import org.kurento.client.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SfuHandler extends TextWebSocketHandler {

    @Autowired
    private KurentoClient kurentoClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // roomId -> Room
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    // sessionId -> UserSession
    private final Map<String, UserSession> sessionMap = new ConcurrentHashMap<>();

    private static class Room {
        private final String roomId;
        private final MediaPipeline pipeline;
        private final Map<String, UserSession> participants = new ConcurrentHashMap<>();

        Room(String roomId, MediaPipeline pipeline) {
            this.roomId = roomId;
            this.pipeline = pipeline;
        }

        void addParticipant(UserSession u) { participants.put(u.userId, u); }
        void removeParticipant(String userId) {
            UserSession u = participants.remove(userId);
            if (u != null) u.cleanup();
        }
        Collection<UserSession> getParticipants() { return participants.values(); }
        UserSession getParticipant(String userId) { return participants.get(userId); }
        MediaPipeline getPipeline() { return pipeline; }
        void cleanup() {
            participants.values().forEach(UserSession::cleanup);
            participants.clear();
            pipeline.release();
        }
    }

    private static class UserSession {
        final String userId;
        final WebSocketSession ws;
        final WebRtcEndpoint publisher; // 내가 보내는용
        final Map<String, WebRtcEndpoint> subscribers = new ConcurrentHashMap<>(); // key = senderId (상대)

        private final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        private final Thread senderThread;

        UserSession(String userId, WebSocketSession ws, WebRtcEndpoint publisher) {
            this.userId = userId;
            this.ws = ws;
            this.publisher = publisher;

            senderThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        String msg = q.take();
                        synchronized (ws) {
                            if (ws.isOpen()) ws.sendMessage(new TextMessage(msg));
                        }
                    }
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    System.err.println("메시지 전송 실패: " + e.getMessage());
                }
            }, "sfu-sender-" + userId);
            senderThread.start();
        }

        void send(String msg) { q.offer(msg); }

        void addSubscriber(String senderId, WebRtcEndpoint ep) { subscribers.put(senderId, ep); }
        WebRtcEndpoint getSubscriber(String senderId) { return subscribers.get(senderId); }
        void removeSubscriber(String senderId) {
            WebRtcEndpoint ep = subscribers.remove(senderId);
            if (ep != null) ep.release();
        }

        void cleanup() {
            subscribers.values().forEach(WebRtcEndpoint::release);
            subscribers.clear();
            if (publisher != null) publisher.release();
            senderThread.interrupt();
            try { if (ws.isOpen()) ws.close(); } catch (IOException ignore) {}
        }

        String roomId;
        void setRoomId(String r) { roomId = r; }
        String getRoomId() { return roomId; }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        System.out.println("SFU WebSocket 연결 수립: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            System.out.println("받은 원본 메시지: " + message.getPayload());
            JsonNode data = objectMapper.readTree(message.getPayload());
            String type = data.has("type") ? data.get("type").asText()
                    : data.has("id")   ? data.get("id").asText()
                    : null;
            if (type == null) return;

            switch (type) {
                case "join-room" -> handleJoinRoom(session, data);
                case "process-sdp-offer" -> handleProcessSdpOffer(session, data);
                case "receive-video-from" -> handleReceiveVideoFrom(session, data);
                case "on-ice-candidate" -> handleOnIceCandidate(session, data);
                case "leave-room" -> handleLeaveRoom(session);
                case "video-status", "audio-status" -> { /* no-op */ }
                default -> System.out.println("알 수 없는 타입: " + type);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Room createRoom(String roomId) {
        MediaPipeline pipeline = kurentoClient.createMediaPipeline();
        System.out.println("새 SFU 방 생성: " + roomId);
        return new Room(roomId, pipeline);
    }

    private void handleJoinRoom(WebSocketSession ws, JsonNode data) throws IOException {
        String roomId = data.get("roomId").asText();
        String userId = data.get("userId").asText();

        Room room = rooms.computeIfAbsent(roomId, this::createRoom);

        // 퍼블리셔 엔드포인트 (한 사람당 1개)
        WebRtcEndpoint pub = new WebRtcEndpoint.Builder(room.getPipeline()).build();

        UserSession user = new UserSession(userId, ws, pub);
        user.setRoomId(roomId);
        sessionMap.put(ws.getId(), user);
        room.addParticipant(user);

        // 서버→브라우저 ICE (publisher용)
        pub.addIceCandidateFoundListener((EventListener<IceCandidateFoundEvent>) ev -> {
            try {
                user.send(createMessage("ice-candidate", Map.of(
                        "candidate", toJsonCandidate(ev.getCandidate()),
                        "kind", "publisher" // 클라에서 로컬 피어에 꽂기 위한 힌트
                )));
            } catch (IOException e) {
                System.err.println("ICE candidate 전송 실패: " + e.getMessage());
            }
        });

        // 기존 참가자들에게 새 사용자 알림
        for (UserSession u : room.getParticipants()) {
            if (!u.userId.equals(userId)) {
                u.send(createMessage("new-participant-arrived", Map.of("userId", userId)));
            }
        }

        // 새 사용자에게 기존 참가자 목록 전송
        List<String> existing = room.getParticipants().stream()
                .filter(u -> !u.userId.equals(userId))
                .map(u -> u.userId).toList();

        user.send(createMessage("existing-participants", Map.of("participants", existing)));

        System.out.println("사용자 " + userId + " 입장, 방 " + roomId + " (총 " + room.getParticipants().size() + ")");
    }

    // 퍼블리셔(로컬 SendOnly 피어)의 SDP 처리
    private void handleProcessSdpOffer(WebSocketSession ws, JsonNode data) throws IOException {
        UserSession user = sessionMap.get(ws.getId());
        if (user == null) return;

        String sdpOffer = data.get("sdpOffer").asText();
        String sdpAnswer = user.publisher.processOffer(sdpOffer);

        // 먼저 SDP Answer 전송
        user.send(createMessage("process-sdp-answer", Map.of("sdpAnswer", sdpAnswer)));
        // ICE 수집 시작
        user.publisher.gatherCandidates();

        System.out.println("SDP Offer 처리 완료(publisher) - " + user.userId);
    }

    // 특정 senderId의 미디어를 수신하려는(RecvOnly) 피어 처리
    private void handleReceiveVideoFrom(WebSocketSession ws, JsonNode data) throws IOException {
        UserSession viewer = sessionMap.get(ws.getId());
        if (viewer == null) return;

        String senderId = data.get("sender").asText();
        String sdpOffer = data.get("sdpOffer").asText();

        Room room = rooms.get(viewer.getRoomId());
        if (room == null) return;

        UserSession sender = room.getParticipant(senderId);
        if (sender == null) return;

        // 이미 있으면 재사용 방지
        if (viewer.getSubscriber(senderId) != null) return;

        // 구독자용 엔드포인트 생성
        WebRtcEndpoint sub = new WebRtcEndpoint.Builder(room.getPipeline()).build();
        viewer.addSubscriber(senderId, sub);

        // 서버→브라우저 ICE (subscriber용) : senderId 힌트 포함
        sub.addIceCandidateFoundListener((EventListener<IceCandidateFoundEvent>) ev -> {
            try {
                viewer.send(createMessage("ice-candidate", Map.of(
                        "candidate", toJsonCandidate(ev.getCandidate()),
                        "sender", senderId // 이 후보는 senderId에 해당하는 구독 피어로
                )));
            } catch (IOException e) {
                System.err.println("ICE candidate 전송 실패(sub): " + e.getMessage());
            }
        });

        // 연결: sender.publisher -> viewer.subscriber(senderId)
        sender.publisher.connect(sub);

        // 구독 피어의 SDP 처리
        String sdpAnswer = sub.processOffer(sdpOffer);
        viewer.send(createMessage("receive-video-answer", Map.of(
                "sender", senderId,
                "sdpAnswer", sdpAnswer
        )));
        sub.gatherCandidates();

        System.out.println("구독 연결 완료: " + senderId + " → " + viewer.userId);
    }

    // 브라우저→서버 ICE 후보 처리
    private void handleOnIceCandidate(WebSocketSession ws, JsonNode data) {
        UserSession user = sessionMap.get(ws.getId());
        if (user == null) return;

        try {
            JsonNode c = data.get("candidate");
            if (c == null || !c.has("candidate") || c.get("candidate").asText().isBlank()) return;

            IceCandidate cand = new IceCandidate(
                    c.get("candidate").asText(),
                    c.has("sdpMid") ? c.get("sdpMid").asText() : null,
                    c.has("sdpMLineIndex") ? c.get("sdpMLineIndex").asInt() : null
            );

            // 구독(RecvOnly) 피어의 후보인지 구분 (클라에서 sender 넣어 보냄)
            if (data.has("sender")) {
                String senderId = data.get("sender").asText();
                WebRtcEndpoint sub = user.getSubscriber(senderId);
                if (sub != null) sub.addIceCandidate(cand);
            } else {
                // 로컬 퍼블리셔 피어
                user.publisher.addIceCandidate(cand);
            }
        } catch (Exception e) {
            System.err.println("ICE candidate 처리 오류: " + e.getMessage());
        }
    }

    private Map<String, Object> toJsonCandidate(IceCandidate c) {
        Map<String, Object> m = new HashMap<>();
        m.put("candidate", c.getCandidate());
        m.put("sdpMid", c.getSdpMid());
        m.put("sdpMLineIndex", c.getSdpMLineIndex());
        return m;
    }

    private String createMessage(String type, Map<String, Object> data) throws IOException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", type);
        m.putAll(data);
        return objectMapper.writeValueAsString(m);
    }

    private void handleLeaveRoom(WebSocketSession ws) {
        UserSession user = sessionMap.remove(ws.getId());
        if (user == null) return;

        String roomId = user.getRoomId();
        String userId = user.userId;

        Room room = rooms.get(roomId);
        if (room != null) {
            room.removeParticipant(userId);
            for (UserSession p : room.getParticipants()) {
                try {
                    p.send(createMessage("participant-left", Map.of("userId", userId)));
                } catch (IOException ignore) {}
            }
            if (room.getParticipants().isEmpty()) {
                room.cleanup();
                rooms.remove(roomId);
                System.out.println("빈 방 제거: " + roomId);
            }
        }
        System.out.println("퇴장: " + userId + " / " + roomId);
    }

    @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus st) {
        handleLeaveRoom(s);
        System.out.println("WS 종료: " + s.getId());
    }
    @Override public void handleTransportError(WebSocketSession s, Throwable ex) {
        System.err.println("WS 오류: " + ex.getMessage());
        handleLeaveRoom(s);
    }
}
