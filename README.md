# TicketSystem

`TicketSystem` is a lightweight Minecraft ticket plugin for Spigot/Paper servers.

Players can create a support ticket with `/ac <message>`, and staff members with permission can accept it, answer directly through chat, and close it automatically after the reply is sent.

The plugin is designed for servers that need a simple in-game support workflow without external services or complex setup.

## Features

- Player ticket creation with `/ac <message>`
- Instant notification for online staff
- Quick accept button in chat
- Ticket GUI panel with `/tickets`
- Chat-based replies to the player
- Automatic ticket closing after reply
- Reminder system for unanswered tickets
- Auto-release if a ticket is held too long
- One active ticket per player
- Optional `StaffWork` integration:
  - staff can still see new tickets
  - accepting and replying can be restricted to active shifts

## Compatibility

- Minecraft `1.16.5` to `1.21.11`
- Spigot / Paper based servers
- Java `11+`

## Commands

- `/ac <message>` - create a ticket
- `/tickets` - open the ticket panel
- `/tickets accept <player-uuid>` - accept a ticket manually

## Permission

- `ticketsystem.admin` - access to ticket handling and ticket panel

## How It Works

1. A player creates a ticket with `/ac <message>`.
2. Online staff members receive a notification.
3. A staff member accepts the ticket from chat or from the GUI panel.
4. The staff member writes a normal chat message.
5. That chat message is sent to the player as the ticket answer.
6. The ticket closes automatically.

## Configuration

Main settings are stored in [`src/main/resources/config.yml`](/C:/Users/artem/Desktop/test/TicketSystem/src/main/resources/config.yml).

Available configuration includes:

- admin permission node
- reminder interval
- auto-release time
- GUI messages
- ticket messages
- shift-required messages for `StaffWork` integration

## Build

Requirements:

- Java `11` or newer
- Maven

Build command:

```bash
mvn clean package
```

Compiled jar:

- [`target/TicketSystem-1.0.0.jar`](/C:/Users/artem/Desktop/test/TicketSystem/target/TicketSystem-1.0.0.jar)

## Source Structure

- [`src/main/java/me/kawasaki/tickets/TicketSystemPlugin.java`](/C:/Users/artem/Desktop/test/TicketSystem/src/main/java/me/kawasaki/tickets/TicketSystemPlugin.java) - main plugin logic, commands, GUI, reminders, ticket handling
- [`src/main/java/me/kawasaki/tickets/models/Ticket.java`](/C:/Users/artem/Desktop/test/TicketSystem/src/main/java/me/kawasaki/tickets/models/Ticket.java) - ticket data model
- [`src/main/resources/plugin.yml`](/C:/Users/artem/Desktop/test/TicketSystem/src/main/resources/plugin.yml) - plugin metadata and commands
- [`src/main/resources/config.yml`](/C:/Users/artem/Desktop/test/TicketSystem/src/main/resources/config.yml) - plugin configuration

## StaffWork Integration

If `StaffWork` is installed on the server, `TicketSystem` can check whether a staff member is currently on shift.

In this mode:

- staff still receive notifications about new tickets
- staff can be restricted from accepting or answering tickets while off shift

If `StaffWork` is not installed, `TicketSystem` works as a standalone plugin.

## Notes

- The plugin stores ticket data in its config file.
- Console can manage the plugin as admin where applicable.
- The plugin uses a simple workflow intentionally to keep moderation fast and clear.
