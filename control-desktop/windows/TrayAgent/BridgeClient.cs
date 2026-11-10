using System.Buffers.Binary;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Tenshu.TrayAgent;

/// <summary>
/// Client for the Control daemon's JSON native bridge (docs/protocol/desktop-ipc.md §6.2), the C# twin of
/// macos/TrayAgent/BridgeClient.swift: one synchronous request/response per connection over the AF_UNIX
/// socket, framed <c>[4-byte BE length][1-byte type = 0][UTF-8 JSON]</c>.
///
/// ⚠ Unlike the Swift peers, this client checks who is serving the socket before sending anything: the
/// listener must belong to this agent's own MSIX package family (<see cref="PeerPackage"/>). A process that
/// bound the path first is refused.
/// </summary>
public sealed class BridgeClient
{
    private const byte TypeRequest = 0;
    private const int MaxFrame = 16 * 1024 * 1024;

    private readonly string _socketPath;

    public BridgeClient(string? socketPath = null) => _socketPath = socketPath ?? DefaultSocketPath();

    /// <summary>Mirrors core-common's SocketPathResolver on Windows: <c>TENSHU_IPC_DIR</c>, else <c>%USERPROFILE%\.tenshu</c>.</summary>
    public static string DefaultSocketDir()
    {
        var dir = Environment.GetEnvironmentVariable("TENSHU_IPC_DIR");
        if (!string.IsNullOrWhiteSpace(dir)) return dir;
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".tenshu");
    }

    public static string DefaultSocketPath() => Path.Combine(DefaultSocketDir(), "bridge.sock");

    public bool IsReachable()
    {
        try { Call("ping"); return true; } catch { return false; }
    }

    /// <summary>Sends <paramref name="method"/>; returns the reply's <c>result</c> object. Throws <see cref="BridgeException"/>.</summary>
    public JsonObject Call(string method, IDictionary<string, string>? parameters = null)
    {
        using var socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
        try
        {
            socket.Connect(new UnixDomainSocketEndPoint(_socketPath));
        }
        catch (SocketException e)
        {
            throw new BridgeException($"Control is not reachable at {_socketPath} ({e.SocketErrorCode})");
        }

        var refusal = PeerPackage.CheckServer(socket);
        if (refusal != null) throw new BridgeException($"refusing the bridge server: {refusal}");

        var request = new JsonObject
        {
            ["v"] = 1,
            ["id"] = Guid.NewGuid().ToString(),
            ["method"] = method,
            ["params"] = ToJson(parameters),
        };
        WriteFrame(socket, Encoding.UTF8.GetBytes(request.ToJsonString()));

        var reply = JsonNode.Parse(ReadFrame(socket)) as JsonObject
            ?? throw new BridgeException("malformed bridge response");
        if (reply["error"] is JsonObject error)
        {
            throw new BridgeException(error["message"]?.GetValue<string>() ?? "bridge error");
        }
        return reply["result"] as JsonObject ?? new JsonObject();
    }

    private static JsonObject ToJson(IDictionary<string, string>? parameters)
    {
        var obj = new JsonObject();
        if (parameters != null) foreach (var (k, v) in parameters) obj[k] = v;
        return obj;
    }

    private static void WriteFrame(Socket socket, byte[] payload)
    {
        var frame = new byte[5 + payload.Length];
        BinaryPrimitives.WriteUInt32BigEndian(frame, (uint)payload.Length);
        frame[4] = TypeRequest;
        payload.CopyTo(frame, 5);
        var sent = 0;
        while (sent < frame.Length) sent += socket.Send(frame, sent, frame.Length - sent, SocketFlags.None);
    }

    private static byte[] ReadFrame(Socket socket)
    {
        var header = ReadExactly(socket, 5);
        var length = BinaryPrimitives.ReadInt32BigEndian(header);
        if (length < 0 || length > MaxFrame) throw new BridgeException($"invalid frame length {length}");
        return ReadExactly(socket, length);
    }

    private static byte[] ReadExactly(Socket socket, int count)
    {
        var buffer = new byte[count];
        var read = 0;
        while (read < count)
        {
            var n = socket.Receive(buffer, read, count - read, SocketFlags.None);
            if (n <= 0) throw new BridgeException("socket closed mid-frame");
            read += n;
        }
        return buffer;
    }
}

public sealed class BridgeException(string message) : Exception(message);
