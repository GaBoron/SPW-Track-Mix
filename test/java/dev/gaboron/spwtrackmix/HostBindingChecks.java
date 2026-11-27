package dev.gaboron.spwtrackmix;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.ObjectName;
import java.util.zip.ZipFile;

/** Checks the native entry points against the installed host archive without starting SPW. */
final class HostBindingChecks {
    public static void main(String[] args) throws Exception {
        var loader = HostBindingChecks.class.getClassLoader();
        var signature = MethodType.fromMethodDescriptorString("(Ljava/lang/String;JJI)I", loader);
        var factory = Class.forName("com.moriafly.bass.BASS", false, loader)
                .getDeclaredMethod("\u037f", signature.parameterArray());
        PcmChecks.require(factory.getReturnType() == int.class, "BASS source factory changed");
        new HostPlaybackSource(loader);

        try (var archive = new ZipFile(args[0]);
             var session = new TrackMixSession(Path.of(args[0]).getParent().resolve("unused-track-mix-check"), () -> null);
             var ui = new TrackMixHostUi(session)) {
            var decoder = archive.getEntry("com/xuncorp/pisces/bass/impl/\u052a.class");
            PcmChecks.require(decoder != null, "native file decoder missing");
            try (var stream = archive.getInputStream(decoder)) {
                var model = ClassFile.of().parse(stream.readAllBytes());
                boolean usesFactory = model.methods().stream().flatMap(method -> method.code().stream())
                        .flatMap(code -> code.elementList().stream())
                        .anyMatch(element -> element instanceof InvokeInstruction invoke
                                && invoke.owner().asInternalName().equals(factory.getDeclaringClass().getName().replace('.', '/'))
                                && invoke.name().equalsString(factory.getName())
                                && invoke.type().equalsString(signature.toMethodDescriptorString()));
                PcmChecks.require(usesFactory, "native decoder no longer calls the PCM hook");
            }
            var navigation = ui.getNavigation();
            String owner = navigation.getDeclaringClass().getName().replace('.', '/');
            String descriptor = MethodType.methodType(navigation.getReturnType(), navigation.getParameterTypes())
                    .toMethodDescriptorString();
            String caller = ui.getNavigationCaller().getName().replace('.', '/');
            var entry = archive.getEntry(caller + ".class");
            PcmChecks.require(entry != null, "native navigation caller missing");
            try (var stream = archive.getInputStream(entry)) {
                var model = ClassFile.of().parse(stream.readAllBytes());
                long sites = model.methods().stream().flatMap(method -> method.code().stream())
                        .flatMap(code -> code.elementList().stream())
                        .filter(element -> element instanceof InvokeInstruction invoke
                                && invoke.owner().asInternalName().equals(owner)
                                && invoke.name().equalsString(navigation.getName())
                                && invoke.type().equalsString(descriptor))
                        .count();
                PcmChecks.require(sites == 1, "native navigation hook call must be unique");
            }
        }
        var name = new ObjectName("dev.gaboron.spwtrackmix:type=Mixer,version=1");
        var server = ManagementFactory.getPlatformMBeanServer();
        try (var session = new TrackMixSession(Path.of(args[0]).getParent().resolve("unused-track-mix-check"), () -> null);
             var link = new TrackMixLink(session)) {
            var state = (Map<?, ?>) server.getAttribute(name, "State");
            PcmChecks.require(((int[]) state.get("levels")).length == 6, "AMLL mixer levels unavailable");
            PcmChecks.require(server.invoke(name, "spectrum", new Object[]{"fixture", 0L},
                    new String[]{"java.lang.String", "long"}) == null, "no source must report no mixed spectrum");
        }
        PcmChecks.require(!server.isRegistered(name), "AMLL mixer bridge remains registered");
        System.out.println("Installed SPW bindings verified: file and active sources, menu, theme, dialog and AMLL endpoint.");
    }
}
