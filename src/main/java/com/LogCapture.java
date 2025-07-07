package com;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class LogCapture extends JavaPlugin {
    
    // Pattern data structure
    private static class PatternConfig {
        Pattern pattern;
        int above;
        int below;
        
        PatternConfig(Pattern pattern, int above, int below) {
            this.pattern = pattern;
            this.above = above;
            this.below = below;
        }
    }
    
    private List<PatternConfig> patternConfigs;
    private File outputFile;
    private ConcurrentLinkedQueue<String> logQueue;
    private BukkitRunnable logMonitor;
    private SimpleDateFormat dateFormat;
    private long lastFilePosition = 0;
    private File serverLogFile;
    private long maxFileSize = 10 * 1024 * 1024; // 10MB default
    private int currentFileNumber = 1;
    private List<String> recentLines = new ArrayList<>();
    private static final int MAX_RECENT_LINES = 100; // Keep last 100 lines for context
    private Map<PatternConfig, Integer> pendingBelowLines = new HashMap<>();
    private String webhookUrl;
    
    @Override
    public void onEnable() {
        this.logQueue = new ConcurrentLinkedQueue<>();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        this.patternConfigs = new ArrayList<>();
        this.pendingBelowLines = new HashMap<>();
        
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        saveDefaultConfig();
        loadConfiguration();
        
        // Find server log file
        findServerLogFile();
        
        // Start monitoring
        startLogMonitor();
        
        getLogger().info("LogCapture plugin enabled!");
        getLogger().info("Monitoring " + patternConfigs.size() + " regex patterns");
        getLogger().info("Output file: " + outputFile.getAbsolutePath());
        getLogger().info("Max file size: " + (maxFileSize / 1024 / 1024) + "MB");
        getLogger().info("Monitoring: " + (serverLogFile != null ? serverLogFile.getAbsolutePath() : "Not found"));
    }
    
    @Override
    public void onDisable() {
        if (logMonitor != null) {
            logMonitor.cancel();
        }
        flushLogs();
        getLogger().info("LogCapture plugin disabled!");
    }
    
    private void loadConfiguration() {
        // Set defaults
        getConfig().addDefault("log-file-path", "logs/latest.log");
        getConfig().addDefault("max-file-size-mb", 10);
        getConfig().addDefault("regex-patterns", new ArrayList<>());
        getConfig().addDefault("webhook-url", "");
        getConfig().options().copyDefaults(true);
        saveConfig();
        
        // Load max file size
        this.maxFileSize = getConfig().getInt("max-file-size-mb", 10) * 1024 * 1024;
        
        // Load webhook URL
        this.webhookUrl = getConfig().getString("webhook-url", "");
        
        // Load patterns
        patternConfigs.clear();
        List<Map<?, ?>> patterns = getConfig().getMapList("regex-patterns");
        
        if (patterns.isEmpty()) {
            // Create default pattern if none exist
            PatternConfig defaultConfig = new PatternConfig(
                Pattern.compile(".*Bukkit.*"), 0, 0
            );
            patternConfigs.add(defaultConfig);
            getLogger().warning("No regex patterns configured! Using default pattern: .*Bukkit.*");
        } else {
            for (Map<?, ?> patternMap : patterns) {
                String regex = (String) patternMap.get("regex");
                int above = patternMap.containsKey("above") ? (Integer) patternMap.get("above") : 0;
                int below = patternMap.containsKey("below") ? (Integer) patternMap.get("below") : 0;
                
                if (regex != null && !regex.isEmpty()) {
                    try {
                        PatternConfig config = new PatternConfig(
                            Pattern.compile(regex), above, below
                        );
                        patternConfigs.add(config);
                    } catch (Exception e) {
                        getLogger().warning("Invalid regex pattern: " + regex + " - " + e.getMessage());
                    }
                }
            }
        }
        
        // Initialize output file
        updateOutputFile();
        
        try {
            if (!outputFile.exists()) {
                outputFile.createNewFile();
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not create output file", e);
        }
    }
    
    private void updateOutputFile() {
        String fileName = ("captured_logs.txt");
        if (fileName.contains(".")) {
            String name = fileName.substring(0, fileName.lastIndexOf('.'));
            String ext = fileName.substring(fileName.lastIndexOf('.'));
            this.outputFile = new File(getDataFolder(), name + "_" + currentFileNumber + ext);
        } else {
            this.outputFile = new File(getDataFolder(), fileName + "_" + currentFileNumber + ".txt");
        }
    }
    
    private void checkFileSize() {
        if (outputFile.exists() && outputFile.length() >= maxFileSize) {
            currentFileNumber++;
            updateOutputFile();
            getLogger().info("Created new log file: " + outputFile.getName());
        }
    }
    
    private void findServerLogFile() {
        String configPath = getConfig().getString("log-file-path", "logs/latest.log");
        
        // Try configured path first
        File configFile = new File(configPath);
        if (configFile.exists() && configFile.canRead()) {
            serverLogFile = configFile;
            try {
                lastFilePosition = configFile.length();
            } catch (Exception e) {
                lastFilePosition = 0;
            }
            return;
        }
        
        // Fallback to common locations
        String[] logPaths = {
            "logs/latest.log",
            "../logs/latest.log",
            "server.log",
            "../server.log"
        };
        
        for (String path : logPaths) {
            File logFile = new File(path);
            if (logFile.exists() && logFile.canRead()) {
                serverLogFile = logFile;
                try {
                    lastFilePosition = logFile.length();
                } catch (Exception e) {
                    lastFilePosition = 0;
                }
                getLogger().warning("Configured log file not found, using fallback: " + path);
                break;
            }
        }
    }
    
    private void startLogMonitor() {
        logMonitor = new BukkitRunnable() {
            @Override
            public void run() {
                if (serverLogFile != null && serverLogFile.exists()) {
                    readNewLogLines();
                }
                flushLogs();
            }
        };
        logMonitor.runTaskTimerAsynchronously(this, 40L, 40L);
    }
    
    private void readNewLogLines() {
        try {
            long currentFileSize = serverLogFile.length();
            
            if (currentFileSize < lastFilePosition) {
                lastFilePosition = 0;
            }
            
            if (currentFileSize > lastFilePosition) {
                try (RandomAccessFile raf = new RandomAccessFile(serverLogFile, "r")) {
                    raf.seek(lastFilePosition);
                    String line;
                    while ((line = raf.readLine()) != null) {
                        processLine(line);
                    }
                    lastFilePosition = raf.getFilePointer();
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Error reading log file", e);
        }
    }
    
    private void processLine(String line) {
        // Add to recent lines buffer
        recentLines.add(line);
        if (recentLines.size() > MAX_RECENT_LINES) {
            recentLines.remove(0);
        }
        
        // Check if we need to capture lines below from previous matches
        List<PatternConfig> completedPatterns = new ArrayList<>();
        for (Map.Entry<PatternConfig, Integer> entry : pendingBelowLines.entrySet()) {
            if (entry.getValue() > 0) {
                captureContextLine(line, entry.getKey());
                entry.setValue(entry.getValue() - 1);
                if (entry.getValue() == 0) {
                    completedPatterns.add(entry.getKey());
                }
            }
        }
        
        // Remove completed patterns
        for (PatternConfig pattern : completedPatterns) {
            pendingBelowLines.remove(pattern);
        }
        
        // Check if current line matches any pattern
        for (PatternConfig config : patternConfigs) {
            if (config.pattern.matcher(line).find()) {
                // Capture lines above
                if (config.above > 0) {
                    int startIndex = Math.max(0, recentLines.size() - config.above - 1);
                    for (int i = startIndex; i < recentLines.size() - 1; i++) {
                        captureContextLine(recentLines.get(i), config);
                    }
                }
                
                // Capture the matching line
                captureMatchLine(line, config);
                
                // Set up to capture lines below
                if (config.below > 0) {
                    pendingBelowLines.put(config, config.below);
                }
            }
        }
    }
    
    private String trimTimestamp(String line) {
        // Remove timestamps like [14:20:23] or [14:20:23 INFO] from the beginning
        if (line.matches("^\\[\\d{2}:\\d{2}:\\d{2}.*?\\].*")) {
            int closeBracket = line.indexOf(']');
            if (closeBracket != -1 && closeBracket + 1 < line.length()) {
                return line.substring(closeBracket + 1).trim();
            }
        }
        return line;
    }
    
    private void sendToDiscord(String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }
        
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(webhookUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    
                    String jsonPayload = "{\"content\":\"```" + message.replace("\"", "\\\"") + "```\"}";
                    
                    try (OutputStream os = conn.getOutputStream()) {
                        byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }
                    
                    conn.getResponseCode();
                    conn.disconnect();
                    
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Failed to send webhook message", e);
                }
            }
        }.runTaskAsynchronously(LogCapture.this);
    }
    
    private void captureMatchLine(String line, PatternConfig config) {
        String logLine = String.format("MATCH [%s]: %s", 
            config.pattern.pattern().substring(0, Math.min(20, config.pattern.pattern().length())), 
            line);
        logQueue.offer(logLine);
        sendToDiscord(logLine);
    }
    
    private void captureContextLine(String line, PatternConfig config) {
        String logLine = String.format("CONTEXT [%s]: %s", 
            config.pattern.pattern().substring(0, Math.min(20, config.pattern.pattern().length())), 
            line);
        logQueue.offer(logLine);
        sendToDiscord(logLine);
    }
    
    private void flushLogs() {
        if (logQueue.isEmpty()) return;
        
        checkFileSize();
        
        try (FileWriter fw = new FileWriter(outputFile, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            
            String logLine;
            while ((logLine = logQueue.poll()) != null) {
                bw.write(logLine);
                bw.newLine();
            }
            bw.flush();
            
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error writing to log file", e);
        }
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "LogCapture Commands:");
        sender.sendMessage(ChatColor.YELLOW + "/logcap reload - Reload configuration");
        sender.sendMessage(ChatColor.YELLOW + "/logcap status - Show current status");
        sender.sendMessage(ChatColor.YELLOW + "/logcap help - Show this help message");
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("logcap")) {
            return false;
        }
        
        if (!sender.hasPermission("logcapture.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
            return true;
        }
        
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "help":
                showHelp(sender);
                break;
                
            case "reload":
                reloadConfig();
                loadConfiguration();
                sender.sendMessage(ChatColor.GREEN + "Configuration reloaded!");
                break;
                
            case "status":
                sender.sendMessage(ChatColor.GOLD + "LogCapture Status:");
                sender.sendMessage(ChatColor.YELLOW + "Patterns: " + patternConfigs.size());
                for (int i = 0; i < patternConfigs.size(); i++) {
                    PatternConfig config = patternConfigs.get(i);
                    sender.sendMessage(ChatColor.GRAY + "  " + (i + 1) + ". " + config.pattern.pattern() + 
                                     " (above: " + config.above + ", below: " + config.below + ")");
                }
                sender.sendMessage(ChatColor.YELLOW + "Current file: " + outputFile.getName());
                sender.sendMessage(ChatColor.YELLOW + "File size: " + (outputFile.length() / 1024) + "KB / " + (maxFileSize / 1024 / 1024) + "MB");
                sender.sendMessage(ChatColor.YELLOW + "Log file: " + (serverLogFile != null ? serverLogFile.getAbsolutePath() : "Not found"));
                sender.sendMessage(ChatColor.YELLOW + "Webhook: " + (webhookUrl != null && !webhookUrl.isEmpty() ? "Enabled" : "Disabled"));
                break;
                
            default:
                sender.sendMessage(ChatColor.RED + "Unknown command! Use /logcap help for available commands.");
                break;
        }
        
        return true;
    }
}