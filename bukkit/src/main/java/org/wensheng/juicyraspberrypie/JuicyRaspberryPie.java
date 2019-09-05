package org.wensheng.juicyraspberrypie;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public class JuicyRaspberryPie extends JavaPlugin implements Listener{
    final Logger logger = Logger.getLogger("Minecraft");
    private static final Set<Material> blockBreakDetectionTools = EnumSet.of(
            Material.DIAMOND_SWORD,
            Material.GOLDEN_SWORD,
            Material.IRON_SWORD, 
            Material.STONE_SWORD, 
            Material.WOODEN_SWORD);

    private ServerListenerThread serverThread;
    private final List<RemoteSession> sessions = new ArrayList<>();

    private void save_resources(){
        File py_init_file = new File(getDataFolder(), "config.yml");
        if(!py_init_file.exists()){
            this.saveResource("config.yml", false);
        }
        File mcpiFolder = new File(getDataFolder(), "mcpi");
        if(!mcpiFolder.exists()) {
            boolean ok = mcpiFolder.mkdir();
            if (ok) {
                this.saveResource("mcpi/block.py", false);
                this.saveResource("mcpi/connection.py", false);
                this.saveResource("mcpi/event.py", false);
                this.saveResource("mcpi/entity.py", false);
                this.saveResource("mcpi/minecraft.py", false);
                this.saveResource("mcpi/pycmdsvr.py", false);
                this.saveResource("mcpi/util.py", false);
                this.saveResource("mcpi/vec3.py", false);
            } else {
                logger.warning("Could not create mcpi directory in plugin.");
            }
        }
        File ppluginsFolder = new File(getDataFolder(), "pplugins");
        if(!ppluginsFolder.exists()){
            boolean ok = ppluginsFolder.mkdir();
            if(ok){
                this.saveResource("pplugins/README.txt", false);
                this.saveResource("pplugins/examples.py", false);
            } else {
                logger.warning("Could not create pplugins directory in plugin.");
            }
        }
    }
    
    public void onEnable(){
        this.saveDefaultConfig();
        int port = this.getConfig().getInt("port");
        boolean start_pyserver = this.getConfig().getBoolean("start_pyserver");
        
        //create new tcp listener thread
        try {
            serverThread = new ServerListenerThread(this, new InetSocketAddress(port));
            new Thread(serverThread).start();
            logger.info("ThreadListener Started");
        } catch (Exception e) {
            e.printStackTrace();
            logger.warning("Failed to start ThreadListener");
            return;
        }
        //register the events
        getServer().getPluginManager().registerEvents(this, this);
        //setup the schedule to called the tick handler
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new TickHandler(), 1, 1);

        this.save_resources();

        if(start_pyserver){
            String pyexe = getConfig().getString("pyexe", "python.exe");
            String pypath = this.getConfig().getString("pypath", "C:\\Python37");

            logger.info("Starting Python command server using " + pyexe + " in " + pypath);
            ProcessBuilder pb = new ProcessBuilder(pyexe, "mcpi/pycmdsvr.py");
            Map<String, String> envs = pb.environment();
            envs.put("Path", pypath);
            try {
                pb.redirectErrorStream(true);
                pb.directory(this.getDataFolder());
                pb.start();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

    }
    
    public void onDisable(){
        int port = this.getConfig().getInt("pysvr_port");
        boolean start_pyserver = this.getConfig().getBoolean("start_pyserver");
        if(port==0){
            port = 32123;
        }

        if(start_pyserver){
            try {
                Socket socket = new Socket("localhost", port);
                DataOutputStream toPyServer = new DataOutputStream(socket.getOutputStream());
                toPyServer.writeUTF("BYE");
                logger.info("ask py server to shut itself down");
                toPyServer.close();
                socket.close();
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        getServer().getScheduler().cancelTasks(this);
        for (RemoteSession session: sessions) {
            try {
                session.close();
            } catch (Exception e) {
                logger.warning("Failed to close RemoteSession");
                e.printStackTrace();
            }
        }
        serverThread.running = false;
        try {
            serverThread.serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        serverThread = null;
    }
    
    public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command command, @Nonnull String label, String[] args){
        String cmdString;
        int port = this.getConfig().getInt("pysvr_port");
        
        if(args.length<1){
            return false;
        }
        
        if(port==0){
            port = 32123;
        }
        
        try {
            Socket socket = new Socket("localhost", port);
            DataOutputStream toPyServer = new DataOutputStream(socket.getOutputStream());
            BufferedReader fromPyServer = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String cmdLine = String.join(" ", args);
            toPyServer.writeUTF(cmdLine);
            //if(player instanceof Player){
            //    logger.info(player.getName() + ": send to py server: " + args[0]);
            //}
            cmdString = fromPyServer.readLine();
            logger.info("the py server send back " + cmdString);
            toPyServer.close();
            fromPyServer.close();
            socket.close();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return true;
    }
    
    private class TickHandler implements Runnable {
        public void run() {
            Iterator<RemoteSession> sI = sessions.iterator();
            while(sI.hasNext()) {
                RemoteSession s = sI.next();
                if (s.pendingRemoval) {
                    s.close();
                    sI.remove();
                } else {
                    s.tick();
                }
            }
        }
    }
    
    @EventHandler(ignoreCancelled=true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack currentTool = event.getPlayer().getInventory().getItemInMainHand();
        if (!blockBreakDetectionTools.contains(currentTool.getType())) {
            return;
        }
        for (RemoteSession session: sessions) {
            session.queuePlayerInteractEvent(event);
        }
    }
    
    @EventHandler(ignoreCancelled=true)
    public void onChatPosted(AsyncPlayerChatEvent event) {
        for (RemoteSession session: sessions) {
            session.queueChatPostedEvent(event);
        }
    }

    /** called when a new session is established. */
    void handleConnection(RemoteSession newSession) {
        if (checkBanned(newSession)) {
            logger.warning("Kicking " + newSession.getSocket().getRemoteSocketAddress() + " because the IP address has been banned.");
            newSession.kick("You've been banned from this server!");
            return;
        }
        synchronized(sessions) {
            sessions.add(newSession);
        }
    }

    Player getNamedPlayer(String name) {
        if (name == null) return null;
        for(Player p: Bukkit.getOnlinePlayers()){
            if(name.equalsIgnoreCase(p.getName())){
                return p;
            }
        }
        return null;
    }

    private boolean checkBanned(RemoteSession session) {
        Set<String> ipBans = getServer().getIPBans();
        String sessionIp = session.getSocket().getInetAddress().getHostAddress();
        return ipBans.contains(sessionIp);
    }

}