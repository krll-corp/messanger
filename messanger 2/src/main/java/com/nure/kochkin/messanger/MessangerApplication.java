package com.nure.kochkin.messanger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.sql.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

@SpringBootApplication
@RestController
public class MessangerApplication {

	static class Message {
		private final String author;
		private final String content;
		private long timecode;

		public Message(String author, String content) {
			this.author = author;
			this.content = content;
			this.timecode = System.currentTimeMillis();
		}

		public String getAuthor() {
			return author;
		}

		public String getContent() {
			return content;
		}

		public long getTimecode() {
			return timecode;
		}

		public void setTimecode(long timecode) {
			this.timecode = timecode;
		}
	}



	private static final Connection conn;

	static {
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres");
			conn.createStatement().executeUpdate(
			    "CREATE TABLE IF NOT EXISTS chats (" +
			    "id INTEGER PRIMARY KEY," +
			    "people JSONB NOT NULL DEFAULT '[]'," +
			    "messages JSONB NOT NULL DEFAULT '[]'" +
			    ")"
			);
		} catch (ClassNotFoundException | SQLException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}



	@GetMapping("/health")
	public String sayHello() {
		return "OK";
	}




	@GetMapping("/chats/get")
	public List<Message> getMessages(@RequestParam int chatId) {
	    String json = "[]";
	    try (PreparedStatement ps = conn.prepareStatement(
	            "SELECT messages FROM chats WHERE id = ?")) {
	        ps.setInt(1, chatId);
	        ResultSet rs = ps.executeQuery();
	        if (rs.next()) {
	            json = rs.getString(1);
	        }
	    } catch (SQLException e) {
	        e.printStackTrace();
	    }

	    List<Message> out = new ArrayList<>();
	    ObjectMapper om = new ObjectMapper();
	    try {
	        List<Map<String, Object>> list = om.readValue(
	                json, new TypeReference<List<Map<String, Object>>>() {});
	        for (Map<String, Object> m : list) {
	            Message msg = new Message(
	                    (String) m.get("author"),
	                    (String) m.get("content"));
	            msg.setTimecode(((Number) m.get("timecode")).longValue());
	            out.add(msg);
	        }
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	    return out;
	}



    @PostMapping("/chats/post")
    public ResponseEntity<String> postMessage(@RequestBody Message message,
                                              @RequestParam int chatId) {
        ObjectMapper om = new ObjectMapper();

        /* 1. Make sure the chat row exists */
        try (PreparedStatement ensure = conn.prepareStatement(
                "INSERT INTO chats (id) VALUES (?) ON CONFLICT (id) DO NOTHING")) {
            ensure.setInt(1, chatId);
            ensure.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("DB error");
        }

        /* 2. Load current people list */
        List<String> people = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT people FROM chats WHERE id = ?")) {
            ps.setInt(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String json = rs.getString(1);
                people = om.readValue(json, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("DB error");
        }


        if (!people.contains(message.getAuthor())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)  
                                 .body("{\"error\": \"User not in chat\"}");
        }

        /* 4. Load current messages array */
        List<Map<String, Object>> messages = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT messages FROM chats WHERE id = ?")) {
            ps.setInt(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String json = rs.getString(1);
                messages = om.readValue(json,
                        new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("DB error");
        }

        Map<String, Object> m = new HashMap<>();
        m.put("author", message.getAuthor());
        m.put("content", message.getContent());
        m.put("timecode", message.getTimecode());
        messages.add(m);

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE chats SET messages = ?::jsonb WHERE id = ?")) {
            ps.setString(1, om.writeValueAsString(messages));
            ps.setInt(2, chatId);
            ps.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("DB error");
        }

        return ResponseEntity.ok("OK");
    }


    @PostMapping("/chats/attend")
    public ResponseEntity<String> attendChat(@RequestParam int chatId, @RequestBody Map<String,String> body) {
        String person = body.get("person");
        ObjectMapper om = new ObjectMapper();

        try (PreparedStatement ensure = conn.prepareStatement(
                "INSERT INTO chats (id) VALUES (?) ON CONFLICT (id) DO NOTHING")) {
            ensure.setInt(1, chatId);
            ensure.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("DB error");
        }


        List<String> people = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT people FROM chats WHERE id = ?")) {
            ps.setInt(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String json = rs.getString(1);
                people = om.readValue(json, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("DB error");
        }

        if (!people.contains(person)) {
            people.add(person);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE chats SET people = ?::jsonb WHERE id = ?")) {
                ps.setString(1, om.writeValueAsString(people));
                ps.setInt(2, chatId);
                ps.executeUpdate();
            } catch (SQLException | JsonProcessingException e) {
                e.printStackTrace();
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                     .body("DB error");
            }
        }

        return ResponseEntity.ok("OK");
    }



	public static void main(String[] args) {
		SpringApplication.run(MessangerApplication.class, args);
	}
}
