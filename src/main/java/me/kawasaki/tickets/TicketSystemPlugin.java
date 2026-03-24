package me.kawasaki.tickets;

import me.kawasaki.tickets.models.Ticket;
import me.kawasaki.tickets.models.Ticket.TicketStatus;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TicketSystemPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerTickets = new ConcurrentHashMap<>(); // playerId -> ticketId (same as playerId for simplicity)
    private final Map<UUID, UUID> adminTickets = new ConcurrentHashMap<>(); // adminId -> ticketId (playerId)
    private final Map<UUID, Integer> adminPages = new ConcurrentHashMap<>(); // adminId -> current page
    
    private BukkitTask reminderTask;
    private BukkitTask autoReleaseTask;

    private static final int PANEL_SIZE = 54;
    private static final int PANEL_TICKETS_PER_PAGE = 45; // 0..44
    private static final int SLOT_PREV = 45;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_NEXT = 53;

    private org.bukkit.NamespacedKey ticketIdKey;
    private org.bukkit.NamespacedKey navKey;
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadTickets();

        ticketIdKey = new org.bukkit.NamespacedKey(this, "ticket_id");
        navKey = new org.bukkit.NamespacedKey(this, "nav");
        
        getServer().getPluginManager().registerEvents(this, this);
        
        Objects.requireNonNull(getCommand("ac")).setExecutor(this);
        Objects.requireNonNull(getCommand("ac")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("tickets")).setExecutor(this);
        Objects.requireNonNull(getCommand("tickets")).setTabCompleter(this);
        
        // Задача для напоминаний администраторам
        long reminderInterval = getConfig().getLong("reminder-interval", 60) * 20L;
        reminderTask = getServer().getScheduler().runTaskTimer(this, this::checkReminders, reminderInterval, reminderInterval);
        
        // Задача для автоматического освобождения тикетов
        autoReleaseTask = getServer().getScheduler().runTaskTimer(this, this::checkAutoRelease, 20L, 20L);
        
        getLogger().info("TicketSystem включен!");
    }
    
    @Override
    public void onDisable() {
        if (reminderTask != null) {
            reminderTask.cancel();
        }
        if (autoReleaseTask != null) {
            autoReleaseTask.cancel();
        }
        saveTickets();
        getLogger().info("TicketSystem выключен!");
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ac")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам.");
                return true;
            }
            
            Player player = (Player) sender;
            
            // Проверяем, есть ли у игрока уже активный тикет
            if (playerTickets.containsKey(player.getUniqueId())) {
                Ticket existingTicket = tickets.get(playerTickets.get(player.getUniqueId()));
                if (existingTicket != null && existingTicket.getStatus() != TicketStatus.CLOSED) {
                    player.sendMessage(color(getConfig().getString("messages.ticket-exists", "&cУ вас уже есть активный тикет.")));
                    return true;
                }
            }
            
            if (args.length == 0) {
                player.sendMessage(color("&cИспользование: /ac [ТЕКСТ]"));
                return true;
            }
            
            String question = String.join(" ", args);
            createTicket(player, question);
            return true;
        }
        
        if (command.getName().equalsIgnoreCase("tickets")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам.");
                return true;
            }
            
            Player player = (Player) sender;
            
            if (!isAdmin(player)) {
                player.sendMessage(color(getConfig().getString("messages.admin-only", "&cЭта команда доступна только администраторам.")));
                return true;
            }
            
            if (args.length > 0 && args[0].equalsIgnoreCase("accept")) {
                if (args.length < 2) {
                    player.sendMessage(color("&cИспользование: /tickets accept <playerId>"));
                    return true;
                }
                if (!canHandleTicketsNow(player, true)) {
                    return true;
                }
                
                try {
                    UUID playerId = UUID.fromString(args[1]);
                    Ticket ticket = tickets.get(playerId);
                    
                    if (ticket == null) {
                        player.sendMessage(color("&cТикет не найден."));
                        return true;
                    }
                    
                    if (ticket.getStatus() == TicketStatus.CLOSED) {
                        player.sendMessage(color("&cЭтот тикет уже закрыт."));
                        return true;
                    }

                    // Жёсткое правило: кто первый принял тикет — тот и будет его обрабатывать
                    if (ticket.getStatus() == TicketStatus.IN_PROGRESS) {
                        if (ticket.getAdminId() != null && !ticket.getAdminId().equals(player.getUniqueId())) {
                            player.sendMessage(color("&cЭтот тикет уже принят другим администратором."));
                            return true;
                        }
                        // Если это тот же админ — просто сообщаем и показываем вопрос
                        if (ticket.getAdminId() != null && ticket.getAdminId().equals(player.getUniqueId())) {
                            player.sendMessage(color("&eВы уже приняли этот тикет."));
                            player.sendMessage(color("&7Вопрос: &f" + ticket.getQuestion()));
                            player.sendMessage(color("&7Напишите ответ в чат, чтобы закрыть тикет."));
                            return true;
                        }
                    }
                    
                    if (adminTickets.containsKey(player.getUniqueId()) && !adminTickets.get(player.getUniqueId()).equals(ticket.getPlayerId())) {
                        player.sendMessage(color("&cУ вас уже есть активный тикет. Закройте его, чтобы принять новый."));
                        return true;
                    }
                    
                    acceptTicket(ticket, player);
                } catch (IllegalArgumentException e) {
                    player.sendMessage(color("&cНеверный UUID игрока."));
                }
                
                return true;
            }
            
            openTicketPanel(player);
            return true;
        }
        
        return false;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("ac")) {
            if (args.length == 1) {
                return Collections.singletonList("[ТЕКСТ]");
            }
        }
        return Collections.emptyList();
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        
        // Проверяем, есть ли у администратора активный тикет
        if (!isAdmin(player)) {
            return;
        }
        
        UUID ticketId = adminTickets.get(player.getUniqueId());
        if (ticketId == null) {
            return;
        }
        
        Ticket ticket = tickets.get(ticketId);
        if (ticket == null || ticket.getStatus() != TicketStatus.IN_PROGRESS) {
            adminTickets.remove(player.getUniqueId());
            return;
        }
        if (!canHandleTicketsNow(player, false)) {
            event.setCancelled(true);
            return;
        }
        
        // Если администратор пишет в чат и у него есть активный тикет, это ответ
        String message = event.getMessage();
        
        // Проверяем, что сообщение не является командой
        if (message.startsWith("/")) {
            return;
        }
        
        // Отправляем ответ игроку
        Player ticketPlayer = Bukkit.getPlayer(ticket.getPlayerId());
        if (ticketPlayer != null && ticketPlayer.isOnline()) {
            ticketPlayer.sendMessage(color("&7[Администратор " + player.getName() + "] &f" + message));
        }
        
        // Закрываем тикет
        closeTicket(ticket, player);
        
        // Отменяем отправку сообщения в обычный чат
        event.setCancelled(true);
        
        player.sendMessage(color("&aВы ответили на тикет от &e" + ticket.getPlayerName() + "&a. Тикет закрыт."));
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getWhoClicked();
        Inventory inv = event.getInventory();
        
        if (!inv.getViewers().get(0).equals(player)) {
            return;
        }
        
        String title = ChatColor.stripColor(event.getView().getTitle());
        String expectedTitle = ChatColor.stripColor(color(getConfig().getString("messages.ticket-panel-title", "&6Панель тикетов")));

        // Заголовок может содержать пагинацию: "Панель тикетов (1/3)"
        if (!title.startsWith(expectedTitle)) {
            return;
        }
        
        event.setCancelled(true);
        
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }

        // Навигация (prev/next/refresh)
        String nav = getPdcString(meta, navKey);
        if (nav != null) {
            int page = adminPages.getOrDefault(player.getUniqueId(), 0);
            if (nav.equalsIgnoreCase("prev")) {
                if (page > 0) page--;
                adminPages.put(player.getUniqueId(), page);
                openTicketPanel(player, page);
                return;
            }
            if (nav.equalsIgnoreCase("next")) {
                page++;
                adminPages.put(player.getUniqueId(), page);
                openTicketPanel(player, page);
                return;
            }
            if (nav.equalsIgnoreCase("refresh")) {
                openTicketPanel(player, page);
                return;
            }
        }

        // Клик по тикету (читаем UUID из PDC)
        String ticketIdRaw = getPdcString(meta, ticketIdKey);
        if (ticketIdRaw == null) {
            return;
        }

        UUID ticketId;
        try {
            ticketId = UUID.fromString(ticketIdRaw);
        } catch (IllegalArgumentException e) {
            player.sendMessage(color("&cНе удалось прочитать тикет."));
            return;
        }

        Ticket ticket = tickets.get(ticketId);
        if (ticket == null || ticket.getStatus() == TicketStatus.CLOSED) {
            player.sendMessage(color("&cТикет не найден или уже обработан."));
            openTicketPanel(player, adminPages.getOrDefault(player.getUniqueId(), 0));
            return;
        }

        // Проверяем, может ли администратор принять этот тикет
        if (ticket.getStatus() == TicketStatus.IN_PROGRESS && ticket.getAdminId() != null && !ticket.getAdminId().equals(player.getUniqueId())) {
            player.sendMessage(color("&cЭтот тикет уже принят другим администратором."));
            openTicketPanel(player, adminPages.getOrDefault(player.getUniqueId(), 0));
            return;
        }

        // Проверяем, есть ли у администратора уже активный тикет
        if (adminTickets.containsKey(player.getUniqueId()) && !adminTickets.get(player.getUniqueId()).equals(ticket.getPlayerId())) {
            player.sendMessage(color("&cУ вас уже есть активный тикет. Закройте его, чтобы принять новый."));
            openTicketPanel(player, adminPages.getOrDefault(player.getUniqueId(), 0));
            return;
        }
        if (!canHandleTicketsNow(player, true)) {
            return;
        }

        // Принимаем тикет
        acceptTicket(ticket, player);
        player.closeInventory();
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());
        String expectedTitle = ChatColor.stripColor(color(getConfig().getString("messages.ticket-panel-title", "&6Панель тикетов")));
        if (!title.startsWith(expectedTitle)) {
            return;
        }

        // Полностью запрещаем drag в панели тикетов
        event.setCancelled(true);
    }
    
    private void createTicket(Player player, String question) {
        UUID playerId = player.getUniqueId();
        
        // Удаляем старый закрытый тикет, если есть
        if (playerTickets.containsKey(playerId)) {
            UUID oldTicketId = playerTickets.get(playerId);
            Ticket oldTicket = tickets.get(oldTicketId);
            if (oldTicket != null && oldTicket.getStatus() == TicketStatus.CLOSED) {
                tickets.remove(oldTicketId);
                playerTickets.remove(playerId);
            }
        }
        
        Ticket ticket = new Ticket(playerId, player.getName(), question);
        tickets.put(playerId, ticket);
        playerTickets.put(playerId, playerId);
        
        player.sendMessage(color(getConfig().getString("messages.ticket-created", "&aВаш тикет создан!")));
        
        // Уведомляем всех администраторов
        notifyAdmins(ticket);
        
        saveTickets();
    }
    
    private void notifyAdmins(Ticket ticket) {
        String message = color("&e[Тикет] &cНовый тикет от игрока &e" + ticket.getPlayerName() + "&c!");
        
        TextComponent mainText = new TextComponent(message);
        
        TextComponent acceptButton = new TextComponent(color(" &a[Принять]"));
        acceptButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tickets accept " + ticket.getPlayerId()));
        acceptButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
            new Text(color("&aНажмите, чтобы принять тикет\n&7Вопрос: &f" + ticket.getQuestion()))));
        
        TextComponent fullMessage = new TextComponent();
        fullMessage.addExtra(mainText);
        fullMessage.addExtra(acceptButton);
        
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (isAdmin(admin)) {
                admin.spigot().sendMessage(fullMessage);
                if (!isStaffWorkShiftActive(admin)) {
                    admin.sendMessage(color(getConfig().getString("messages.shift-required-notice",
                            "&7Чтобы ответить на тикет, сначала включите смену: &e/sw on")));
                }
            }
        }
        
        // Также отправляем в консоль
        getLogger().info("Новый тикет от " + ticket.getPlayerName() + ": " + ticket.getQuestion());
    }
    
    private void acceptTicket(Ticket ticket, Player admin) {
        // Если тикет уже принят другим администратором
        if (ticket.getStatus() == TicketStatus.IN_PROGRESS && ticket.getAdminId() != null && !ticket.getAdminId().equals(admin.getUniqueId())) {
            admin.sendMessage(color("&cЭтот тикет уже принят другим администратором."));
            return;
        }
        
        // Проверяем, есть ли у администратора уже активный тикет
        if (adminTickets.containsKey(admin.getUniqueId()) && !adminTickets.get(admin.getUniqueId()).equals(ticket.getPlayerId())) {
            admin.sendMessage(color("&cУ вас уже есть активный тикет. Закройте его, чтобы принять новый."));
            return;
        }
        
        ticket.setAdminId(admin.getUniqueId());
        ticket.setAdminName(admin.getName());
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setAcceptedAt(System.currentTimeMillis());
        
        adminTickets.put(admin.getUniqueId(), ticket.getPlayerId());
        
        // Уведомляем игрока
        Player ticketPlayer = Bukkit.getPlayer(ticket.getPlayerId());
        if (ticketPlayer != null && ticketPlayer.isOnline()) {
            String msg = getConfig().getString("messages.ticket-accepted-player", "&aАдминистратор &e{admin} &aпринял ваш тикет!")
                    .replace("{admin}", admin.getName());
            ticketPlayer.sendMessage(color(msg));
            ticketPlayer.sendMessage(color("&7Ваш вопрос: &f" + ticket.getQuestion()));
            ticketPlayer.sendMessage(color("&7Ожидайте ответа администратора в чате..."));
        }
        
        admin.sendMessage(color("&aВы приняли тикет от &e" + ticket.getPlayerName() + "&a."));
        admin.sendMessage(color("&7Вопрос: &f" + ticket.getQuestion()));
        admin.sendMessage(color("&7Напишите ответ в чат, чтобы ответить на тикет."));
        
        saveTickets();
    }
    
    private void closeTicket(Ticket ticket, Player admin) {
        ticket.setStatus(TicketStatus.CLOSED);
        
        // Удаляем из активных тикетов
        playerTickets.remove(ticket.getPlayerId());
        adminTickets.remove(admin.getUniqueId());
        
        // Уведомляем игрока
        Player ticketPlayer = Bukkit.getPlayer(ticket.getPlayerId());
        if (ticketPlayer != null && ticketPlayer.isOnline()) {
            String msg = getConfig().getString("messages.ticket-closed", "&aВаш тикет закрыт.");
            ticketPlayer.sendMessage(color(msg));
        }
        
        admin.sendMessage(color("&aТикет от &e" + ticket.getPlayerName() + "&a закрыт."));
        
        saveTickets();
    }
    
    private void openTicketPanel(Player admin) {
        openTicketPanel(admin, adminPages.getOrDefault(admin.getUniqueId(), 0));
    }

    private void openTicketPanel(Player admin, int page) {
        List<Ticket> openTickets = tickets.values().stream()
                .filter(t -> t.getStatus() == TicketStatus.OPEN ||
                        (t.getStatus() == TicketStatus.IN_PROGRESS && t.getAdminId() != null && t.getAdminId().equals(admin.getUniqueId())))
                .sorted(Comparator.comparingLong(Ticket::getCreatedAt))
                .collect(Collectors.toList());

        int totalPages = Math.max(1, (int) Math.ceil(openTickets.size() / (double) PANEL_TICKETS_PER_PAGE));
        if (page < 0) page = 0;
        if (page > totalPages - 1) page = totalPages - 1;
        adminPages.put(admin.getUniqueId(), page);

        String baseTitle = color(getConfig().getString("messages.ticket-panel-title", "&6Панель тикетов"));
        String title = baseTitle + color(" &7(" + (page + 1) + "/" + totalPages + ")");
        Inventory inv = Bukkit.createInventory(null, PANEL_SIZE, title);

        // Заполняем тикеты (0..44)
        int start = page * PANEL_TICKETS_PER_PAGE;
        int end = Math.min(openTickets.size(), start + PANEL_TICKETS_PER_PAGE);
        if (openTickets.isEmpty()) {
            ItemStack noTickets = new ItemStack(Material.BARRIER);
            ItemMeta meta = noTickets.getItemMeta();
            meta.setDisplayName(color(getConfig().getString("messages.no-tickets", "&cНет доступных тикетов.")));
            noTickets.setItemMeta(meta);
            inv.setItem(22, noTickets);
        } else {
            for (int i = start; i < end; i++) {
                Ticket ticket = openTickets.get(i);

                ItemStack item = new ItemStack(Material.PAPER);
                ItemMeta meta = item.getItemMeta();

                String itemName = getConfig().getString("messages.ticket-item-name", "&eТикет от &c{player}")
                        .replace("{player}", ticket.getPlayerName());
                meta.setDisplayName(color(itemName));

                List<String> lore = new ArrayList<>();
                List<String> configLore = getConfig().getStringList("messages.ticket-item-lore");
                for (String line : configLore) {
                    lore.add(color(line.replace("{question}", ticket.getQuestion())
                            .replace("{status}", ticket.getStatus() == TicketStatus.OPEN ? "Открыт" : "В работе")));
                }
                meta.setLore(lore);

                // Сохраняем UUID тикета в PDC, чтобы не парсить имя из displayName
                setPdcString(meta, ticketIdKey, ticket.getPlayerId().toString());

                item.setItemMeta(meta);
                inv.setItem(i - start, item);
            }
        }

        // Кнопки навигации
        inv.setItem(SLOT_PREV, navItem(Material.ARROW, "&e< Назад", "prev", page > 0));
        inv.setItem(SLOT_REFRESH, navItem(Material.SUNFLOWER, "&aОбновить", "refresh", true));
        inv.setItem(SLOT_NEXT, navItem(Material.ARROW, "&eВперёд >", "next", page < totalPages - 1));

        admin.openInventory(inv);
    }

    private ItemStack navItem(Material mat, String name, String navValue, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? mat : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(enabled ? name : "&7" + ChatColor.stripColor(color(name))));
        setPdcString(meta, navKey, navValue);
        item.setItemMeta(meta);
        return item;
    }

    private String getPdcString(ItemMeta meta, org.bukkit.NamespacedKey key) {
        try {
            org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();
            return pdc.get(key, org.bukkit.persistence.PersistentDataType.STRING);
        } catch (Throwable t) {
            return null;
        }
    }

    private void setPdcString(ItemMeta meta, org.bukkit.NamespacedKey key, String value) {
        try {
            meta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, value);
        } catch (Throwable ignored) {
        }
    }
    
    private void checkReminders() {
        long reminderInterval = getConfig().getLong("reminder-interval", 60) * 1000L;
        
        for (Ticket ticket : tickets.values()) {
            if (ticket.getStatus() != TicketStatus.IN_PROGRESS) {
                continue;
            }
            
            UUID adminId = ticket.getAdminId();
            if (adminId == null) {
                continue;
            }
            
            Player admin = Bukkit.getPlayer(adminId);
            if (admin == null || !admin.isOnline()) {
                continue;
            }
            
            long timeSinceLastReminder = System.currentTimeMillis() - ticket.getLastReminder();
            if (timeSinceLastReminder >= reminderInterval) {
                String msg = getConfig().getString("messages.ticket-reminder-admin", 
                        "&eУ вас есть активный тикет от игрока &c{player}&e. Ответьте на него!")
                        .replace("{player}", ticket.getPlayerName());
                admin.sendMessage(color(msg));
                ticket.setLastReminder(System.currentTimeMillis());
            }
        }
    }
    
    private void checkAutoRelease() {
        long autoReleaseTime = getConfig().getLong("auto-release-time", 300) * 1000L;
        
        List<Ticket> toRelease = new ArrayList<>();
        
        for (Ticket ticket : tickets.values()) {
            if (ticket.getStatus() != TicketStatus.IN_PROGRESS) {
                continue;
            }
            
            UUID adminId = ticket.getAdminId();
            if (adminId == null) {
                continue;
            }
            
            long timeSinceAccepted = System.currentTimeMillis() - ticket.getAcceptedAt();
            if (timeSinceAccepted >= autoReleaseTime) {
                Player admin = Bukkit.getPlayer(adminId);
                // Освобождаем тикет, если администратор не ответил в течение заданного времени
                // (независимо от того, онлайн он или нет)
                toRelease.add(ticket);
            }
        }
        
        for (Ticket ticket : toRelease) {
            UUID adminId = ticket.getAdminId();
            if (adminId != null) {
                adminTickets.remove(adminId);
                
                // Уведомляем администратора, если он онлайн
                Player admin = Bukkit.getPlayer(adminId);
                if (admin != null && admin.isOnline()) {
                    String msg = getConfig().getString("messages.ticket-released", 
                            "&cТикет от игрока &e{player} &cосвобожден, так как вы не ответили.")
                            .replace("{player}", ticket.getPlayerName());
                    admin.sendMessage(color(msg));
                }
            }
            
            ticket.setAdminId(null);
            ticket.setAdminName(null);
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setAcceptedAt(0);
            ticket.setLastReminder(0);
            
            // Уведомляем всех администраторов о новом доступном тикете
            notifyAdmins(ticket);
            
            String msg = getConfig().getString("messages.ticket-released", 
                    "&cТикет от игрока &e{player} &cосвобожден.")
                    .replace("{player}", ticket.getPlayerName());
            
            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (isAdmin(admin) && !admin.getUniqueId().equals(adminId)) {
                    admin.sendMessage(color(msg));
                }
            }
        }
        
        if (!toRelease.isEmpty()) {
            saveTickets();
        }
    }
    
    private String extractPlayerNameFromItemName(String displayName) {
        // Формат: "Тикет от PlayerName" или "Ticket from PlayerName"
        if (displayName.contains("от ")) {
            String[] parts = displayName.split("от ");
            if (parts.length > 1) {
                return ChatColor.stripColor(parts[1].trim());
            }
        }
        if (displayName.contains("from ")) {
            String[] parts = displayName.split("from ");
            if (parts.length > 1) {
                return ChatColor.stripColor(parts[1].trim());
            }
        }
        return null;
    }
    
    private boolean isAdmin(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) {
            return true;
        }
        if (sender instanceof Player) {
            return sender.hasPermission(getConfig().getString("admin-permission", "ticketsystem.admin"));
        }
        return false;
    }

    private boolean canHandleTicketsNow(Player admin, boolean forAccept) {
        if (isStaffWorkShiftActive(admin)) {
            return true;
        }

        String path = forAccept ? "messages.shift-required-accept" : "messages.shift-required-reply";
        String fallback = forAccept
                ? "&cЧтобы принимать тикеты, сначала включите смену: &e/sw on"
                : "&cЧтобы отвечать на тикеты, сначала включите смену: &e/sw on";
        admin.sendMessage(color(getConfig().getString(path, fallback)));
        return false;
    }

    private boolean isStaffWorkShiftActive(Player player) {
        Plugin staffWork = Bukkit.getPluginManager().getPlugin("StaffWork");
        if (staffWork == null || !staffWork.isEnabled()) {
            return true;
        }

        try {
            Method getWorkService = staffWork.getClass().getMethod("getWorkService");
            Object workService = getWorkService.invoke(staffWork);
            if (workService == null) {
                return true;
            }

            Method isWorking = workService.getClass().getMethod("isWorking", String.class);
            Object result = isWorking.invoke(workService, player.getName());
            return result instanceof Boolean && (Boolean) result;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            getLogger().warning("Не удалось проверить StaffWork смену для " + player.getName() + ": " + e.getMessage());
            return true;
        }
    }
    
    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
    
    private void loadTickets() {
        FileConfiguration config = getConfig();
        if (!config.contains("tickets")) {
            return;
        }
        
        for (String key : config.getConfigurationSection("tickets").getKeys(false)) {
            UUID playerId = UUID.fromString(key);
            String playerName = config.getString("tickets." + key + ".playerName");
            String question = config.getString("tickets." + key + ".question");
            String statusStr = config.getString("tickets." + key + ".status", "OPEN");
            
            Ticket ticket = new Ticket(playerId, playerName, question);
            ticket.setStatus(TicketStatus.valueOf(statusStr));
            
            if (config.contains("tickets." + key + ".adminId")) {
                UUID adminId = UUID.fromString(config.getString("tickets." + key + ".adminId"));
                ticket.setAdminId(adminId);
                ticket.setAdminName(config.getString("tickets." + key + ".adminName"));
                ticket.setAcceptedAt(config.getLong("tickets." + key + ".acceptedAt", 0));
                adminTickets.put(adminId, playerId);
            }
            
            tickets.put(playerId, ticket);
            if (ticket.getStatus() != TicketStatus.CLOSED) {
                playerTickets.put(playerId, playerId);
            }
        }
    }
    
    private void saveTickets() {
        FileConfiguration config = getConfig();
        config.set("tickets", null);
        
        for (Map.Entry<UUID, Ticket> entry : tickets.entrySet()) {
            UUID playerId = entry.getKey();
            Ticket ticket = entry.getValue();
            
            String path = "tickets." + playerId.toString();
            config.set(path + ".playerName", ticket.getPlayerName());
            config.set(path + ".question", ticket.getQuestion());
            config.set(path + ".status", ticket.getStatus().name());
            
            if (ticket.getAdminId() != null) {
                config.set(path + ".adminId", ticket.getAdminId().toString());
                config.set(path + ".adminName", ticket.getAdminName());
                config.set(path + ".acceptedAt", ticket.getAcceptedAt());
            }
        }
        
        saveConfig();
    }
}

