import com.roysolberg.java.hdlserver.hdl.Action;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponent;
import com.roysolberg.java.hdlserver.hdl.component.HdlComponentFactory;
import com.roysolberg.java.hdlserver.service.HdlService;
import com.roysolberg.java.hdlserver.util.HdlUtil;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/** Writes a hdlserver.db exactly as a released JAR's Application would, using that JAR's own classes. */
public class MakeLegacyDb {
    public static void main(String[] args) {
        DB database = DBMaker.fileDB(args[0]).fileMmapEnable().checksumHeaderBypass().make();
        ConcurrentMap config = database.hashMap("config").createOrOpen();
        ConcurrentMap components = database.hashMap("components").createOrOpen();
        ConcurrentMap actions = database.hashMap("actions").createOrOpen();
        ConcurrentMap aliases = database.hashMap("actionAliases").createOrOpen();

        config.put("authToken", "LEGACYTOKEN0123456789ABCDEF");

        // One component of every class the factory can produce, plus the two "unsupported" ones.
        Map<Class<?>, HdlComponent> oneOfEach = new LinkedHashMap<>();
        for (int type = 0; type <= 0xFFFF; type++) {
            HdlComponent component = HdlService.getComponent(1, oneOfEach.size() + 1, type, "Remark " + type);
            oneOfEach.putIfAbsent(component.getClass(), component);
        }
        for (HdlComponent component : oneOfEach.values()) {
            components.put(HdlUtil.getComponentId(component), component);
        }

        Action custom = new Action(Action.ActionType.Custom, 1, "Open the garage");
        custom.addCommand(new Action.Command(1, 81, 0xE3D8, 18, 1, null));
        custom.addCommand(new Action.Command(1, 82, 0xE3E0, 1, 2, null));
        actions.put(custom.getId(), custom);

        Action dimmer = new Action(Action.ActionType.Dimmer, 2, "the kitchen");
        dimmer.addAlias("kitchen");
        dimmer.addCommand(new Action.Command(1, 40, 0x0031, 3, null, null));
        dimmer.addCommand(new Action.Command(1, 41, 0x0031, 1, null, null));
        actions.put(dimmer.getId(), dimmer);
        aliases.put("the kitchen", 2);
        aliases.put("kitchen", 2);

        database.commit();
        database.close();
        System.out.println("Wrote " + oneOfEach.size() + " components: " + oneOfEach.keySet());
    }
}
