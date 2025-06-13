package com.nure.kochkin.chatclient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;

/**
 * Single–file Java FX front-end for the Spring-Boot messenger.
 * 1. Login screen – asks for server URL & nickname.
 * 2. Chat screen  – users list at left, messages in centre, composer at bottom.
 * 3. Polls   /chats/get    and  /chats/people   every 2 s.
 */
public class ChatFxClient extends Application {

	/* ───────────────────────── state ───────────────────────── */
	private final RestTemplate http = new RestTemplate();
	private final ObjectMapper json = new ObjectMapper();
	private ScheduledExecutorService poller;

	private String baseUrl = "http://127.0.0.1:8080";
	private int    chatId  = 1;
	private String nickname;

	/* ──────────────────────── UI lists ─────────────────────── */
	private final ObservableList<MessageView> msgViews = FXCollections.observableArrayList();
	private final ListView<MessageView>       msgList  = new ListView<>(msgViews);

	private final ObservableList<String>      people   = FXCollections.observableArrayList();
	private final ListView<String>            peopleList = new ListView<>(people);

	private final TextField input = new TextField();

	/* small record just for the ListView */
	record MessageView(String author, String content) {
		@Override public String toString() { return author + ": " + content; }
	}
	record Message(String author, String content, long timecode) {}

	/* ─────────────────────── Java FX life-cycle ─────────────────────── */
	@Override public void start(Stage stage) {
		stage.setTitle("Messenger FX");
		stage.setScene(buildLoginScene(stage));
		stage.show();
	}

	@Override public void stop() {
		if (poller != null) poller.shutdownNow();
	}

	/* ────────────────────── 1 · Login scene ────────────────────── */
	private Scene buildLoginScene(Stage stage) {
		var urlField  = new TextField(baseUrl);
		var nameField = new TextField();

		var cancel = new Button("Cancel");
		cancel.setOnAction(e -> Platform.exit());

		var next = new Button("Next");
		next.setDefaultButton(true);
		next.setOnAction(e -> {
			baseUrl  = urlField.getText().trim().isEmpty() ? baseUrl  : urlField.getText().trim();
			nickname = nameField.getText().trim();
			if (nickname.isEmpty()) return;

			if (attendChat()) {                       // POST /chats/attend
				stage.setScene(buildChatScene());
				stage.setWidth(900); stage.setHeight(600); stage.centerOnScreen();
				startPolling();
			}
		});

		var grid = new GridPane();
		grid.setPadding(new Insets(40));
		grid.setVgap(25); grid.setHgap(20);
		grid.addRow(0, new Label("Server URL:"), urlField);
		grid.addRow(1, new Label("User name:" ), nameField);
		var bar = new HBox(15, cancel, next); bar.setAlignment(Pos.CENTER_RIGHT);
		grid.add(bar, 1, 2);

		return new Scene(grid, 450, 300);
	}

	/* ────────────────────── 2 · Chat scene ────────────────────── */
	private Scene buildChatScene() {
		/* left – users */
		peopleList.setPrefWidth(220);
		var left = new VBox(new Label("USERS"), peopleList);
		left.setPrefWidth(220);
		left.setStyle("-fx-background-color:#d3eaf5;");
		VBox.setVgrow(peopleList, Priority.ALWAYS);

		/* centre – messages + composer */
		msgList.setMouseTransparent(true);   // read-only
		VBox.setVgrow(msgList, Priority.ALWAYS);

		input.setPromptText("Type a message…");
		input.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) send(); });
		var send = new Button("Send");
		send.setOnAction(e -> send());

		var bottom = new HBox(10, input, send);
		bottom.setPadding(new Insets(10));
		HBox.setHgrow(input, Priority.ALWAYS);

		var centre = new BorderPane(msgList, null, null, bottom, null);

		return new Scene(new BorderPane(centre, null, null, null, left));
	}

	/* ────────────────────── REST helpers ────────────────────── */
	private boolean attendChat() {
		try {
			var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
			var body = json.writeValueAsString(Map.of("person", nickname));
			var resp = http.exchange(baseUrl + "/chats/attend?chatId=" + chatId,
					HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
			return resp.getStatusCode().is2xxSuccessful();
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}
	}

	private void send() {
		var text = input.getText().trim();
		if (text.isEmpty()) return;
		try {
			long ts   = System.currentTimeMillis();
			var pkt   = json.writeValueAsString(Map.of(
					"author", nickname, "content", text, "timecode", ts));
			var hdr   = new HttpHeaders(); hdr.setContentType(MediaType.APPLICATION_JSON);
			http.exchange(baseUrl + "/messages/post?chatId=" + chatId,
					HttpMethod.POST, new HttpEntity<>(pkt, hdr), String.class);
			input.clear();
		} catch (Exception ex) { ex.printStackTrace(); }
	}

	private void fetchMessages() {
		try {
			String raw = http.getForObject(baseUrl + "/messages/get?chatId=" + chatId, String.class);
			List<Message> msgs = json.readValue(raw, new TypeReference<>() {});
			Platform.runLater(() -> {
				msgViews.setAll(msgs.stream()
						.map(m -> new MessageView(m.author(), m.content()))
						.toList());
				if (!msgViews.isEmpty()) msgList.scrollTo(msgViews.size() - 1);
			});
		} catch (Exception ignore) {}
	}

	private void fetchPeople() {
		try {
			String raw = http.getForObject(baseUrl + "/chats/people?chatId=" + chatId, String.class);
			List<String> names = json.readValue(raw, new TypeReference<>() {});
			Platform.runLater(() -> people.setAll(names));
		} catch (Exception ignore) {}
	}

	/* ─────────────────────── polling ─────────────────────── */
	private void startPolling() {
		poller = Executors.newSingleThreadScheduledExecutor();
		poller.scheduleAtFixedRate(this::poll, 0, 2, TimeUnit.SECONDS);
	}
	private void poll() {
		fetchMessages();
		fetchPeople();
	}

	/* ───────────────────────── main ───────────────────────── */
	public static void main(String[] args) { launch(args); }
}
