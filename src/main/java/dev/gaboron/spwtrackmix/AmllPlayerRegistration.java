package dev.gaboron.spwtrackmix;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.util.List;
import java.util.Map;

/** Registers the Track Mix contribution through the public rebuilt-player protocol. */
public final class AmllPlayerRegistration implements AutoCloseable {
    private static final String DOMAIN = "io.github.gaboron.amllspw:";
    private static final String PROVIDER = ",version=1,provider=dev.gaboron.spwtrackmix";

    public interface Panel {
        Map<String, Object> getDescriptor();
        List<Map<String, Object>> render(String session, Map<String, String> context);
        void action(String session, Map<String, String> context, String action, int value);
        void close(String session);
    }

    public interface SpectrumSource {
        Object[] spectrum(String path, long positionMs);
    }

    private final MBeanServer server;
    private final ObjectName panel = ObjectName.getInstance(DOMAIN + "type=PlayerPanel" + PROVIDER);
    private final ObjectName spectrum = ObjectName.getInstance(DOMAIN + "type=SpectrumSource" + PROVIDER);

    AmllPlayerRegistration(MBeanServer server, TrackMixSession session) throws JMException {
        this.server = server;
        server.registerMBean(new StandardMBean(new TrackMixPanel(session), Panel.class), panel);
        try {
            SpectrumSource reader = session::spectrum;
            server.registerMBean(new StandardMBean(reader, SpectrumSource.class), spectrum);
        } catch (JMException | RuntimeException error) {
            try {
                server.unregisterMBean(panel);
            } catch (JMException rollback) {
                error.addSuppressed(rollback);
            }
            throw error;
        }
    }

    @Override
    public void close() throws JMException {
        try {
            server.unregisterMBean(spectrum);
        } finally {
            server.unregisterMBean(panel);
        }
    }
}
