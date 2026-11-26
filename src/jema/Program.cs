using System.Globalization;
using System.Text;
using Jema;

const int channels = 12;
const int bytesPerFrame = channels * sizeof(float);
const int maxWindowFrames = 192000;
const int inspect = 1;
const int read = 2;
ushort[] stereoTracks = [2, 2, 2, 2, 2, 2];

try
{
    switch (args)
    {
        case ["serve"]:
            Serve();
            return 0;
        case ["encode", var raw, var destination, var sampleRate, var frameCount, var metadata]:
            Encode(raw, destination, int.Parse(sampleRate, CultureInfo.InvariantCulture),
                int.Parse(frameCount, CultureInfo.InvariantCulture), metadata);
            return 0;
        default:
            return 2;
    }
}
catch (Exception error) when (IsCodecError(error))
{
    Console.Error.WriteLine(error.Message);
    return 1;
}

void Encode(string raw, string destination, int rate, int frames, string metadata)
{
    var layout = new PcmLayout(rate, stereoTracks);
    using var input = File.OpenRead(raw);
    if (frames <= 0 || input.Length != (long)frames * bytesPerFrame)
        throw new InvalidDataException("The stem PCM length changed before encoding.");
    using var output = File.Open(destination, FileMode.CreateNew, FileAccess.ReadWrite);
    JemaEncoder.Encode(input, output, layout, frames,
        new EncoderOptions { WorkerCount = Math.Clamp(Environment.ProcessorCount, 1, 4) },
        new JemaMetadata(null, Convert.FromBase64String(metadata)));
    output.Flush(flushToDisk: true);
}

void Serve()
{
    using var input = new BinaryReader(new BufferedStream(Console.OpenStandardInput()), Encoding.UTF8);
    using var output = new BinaryWriter(new BufferedStream(Console.OpenStandardOutput(), 64 * 1024), Encoding.UTF8);
    // Requests are serial, with little-endian integers and length-prefixed UTF-8 paths.
    // Closing the parent pipe stops this process even after an unclean host exit.
    byte[] samples = [];
    while (true)
    {
        int operation = input.BaseStream.ReadByte();
        if (operation == -1)
            return;
        if (operation is not (inspect or read))
            throw new InvalidDataException("Unknown codec request.");

        int pathBytes = input.ReadInt32();
        if (pathBytes is <= 0 or > 131072)
            throw new InvalidDataException("Invalid codec request path.");
        string path = Encoding.UTF8.GetString(input.ReadBytes(pathBytes));
        int start = operation == read ? input.ReadInt32() : 0;
        int count = operation == read ? input.ReadInt32() : 0;
        int rate = operation == read ? input.ReadInt32() : 0;
        int frames = operation == read ? input.ReadInt32() : 0;
        try
        {
            using var file = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete);
            using var reader = new JemaReader(file);
            if (!reader.Layout.ChannelsPerTrack.SequenceEqual(stereoTracks)
                || reader.Layout.SampleRate is < 8000 or > 384000 || reader.Frames is <= 0 or > int.MaxValue)
                throw new InvalidDataException("The cache requires six stereo stems on a shared timeline.");

            if (operation == inspect)
            {
                var metadata = reader.ReadMetadata().Application ?? [];
                if (metadata.Length > 4096)
                    throw new InvalidDataException("Invalid stem cache metadata size.");
                output.Write((byte)0);
                output.Write(reader.Layout.SampleRate);
                output.Write((int)reader.Frames);
                output.Write(metadata.Length);
                output.Write(metadata);
            }
            else
            {
                if (rate != reader.Layout.SampleRate || frames != reader.Frames || count is <= 0 or > maxWindowFrames)
                    throw new InvalidDataException("The stem cache layout or requested window is invalid.");
                // Decode the entire requested window before responding so a failed block
                // never leaves a partial response or an audible fragment in the plugin.
                int length = count * bytesPerFrame;
                if (samples.Length < length)
                    samples = new byte[length];
                var pcm = samples.AsSpan(0, length);
                reader.ReadFrames(start, pcm);
                output.Write((byte)0);
                output.Write(pcm);
            }
        }
        catch (Exception error) when (IsCodecError(error))
        {
            var message = Encoding.UTF8.GetBytes(error.Message[..Math.Min(error.Message.Length, 512)]);
            output.Write((byte)1);
            output.Write(message.Length);
            output.Write(message);
        }
        output.Flush();
    }
}

static bool IsCodecError(Exception error) =>
    error is IOException or InvalidDataException or ArgumentException or ArithmeticException
        or FormatException or UnauthorizedAccessException;
