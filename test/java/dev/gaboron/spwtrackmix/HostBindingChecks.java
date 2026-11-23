package dev.gaboron.spwtrackmix;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Checks the native entry points against the installed host archive without starting SPW. */
final class HostBindingChecks {
    public static void main(String[] args) throws Exception {
        var loader = HostBindingChecks.class.getClassLoader();
        var signature = MethodType.fromMethodDescriptorString("(Ljava/lang/String;JJI)I", loader);
        var factory = Class.forName("com.moriafly.bass.BASS", false, loader)
                .getDeclaredMethod("\u037f", signature.parameterArray());
        PcmChecks.require(factory.getReturnType() == int.class, "BASS source factory changed");

        try (var archive = new ZipFile(args[0]);
             var session = new TrackMixSession(Path.of(args[0]).getParent().resolve("unused-track-mix-check"), () -> null);
             var ui = new TrackMixHostUi(session)) {
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
        System.out.println("Installed SPW bindings verified: file-source DSP, menu builder, theme and dialog host.");
    }
}
