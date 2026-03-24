package me.kawasaki.tickets.models;

import java.util.UUID;

public class Ticket {
    private UUID playerId;
    private String playerName;
    private String question;
    private UUID adminId;
    private String adminName;
    private TicketStatus status;
    private long createdAt;
    private long lastReminder;
    private long acceptedAt;

    public enum TicketStatus {
        OPEN,        // Открыт, ожидает принятия
        IN_PROGRESS, // Принят администратором
        CLOSED       // Закрыт
    }

    public Ticket(UUID playerId, String playerName, String question) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.question = question;
        this.status = TicketStatus.OPEN;
        this.createdAt = System.currentTimeMillis();
        this.lastReminder = 0;
        this.acceptedAt = 0;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getQuestion() {
        return question;
    }

    public UUID getAdminId() {
        return adminId;
    }

    public void setAdminId(UUID adminId) {
        this.adminId = adminId;
    }

    public String getAdminName() {
        return adminName;
    }

    public void setAdminName(String adminName) {
        this.adminName = adminName;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastReminder() {
        return lastReminder;
    }

    public void setLastReminder(long lastReminder) {
        this.lastReminder = lastReminder;
    }

    public long getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(long acceptedAt) {
        this.acceptedAt = acceptedAt;
    }
}

