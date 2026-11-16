using System.Reflection;
using System.Text.RegularExpressions;
using Tenshu.TrayAgent;
using Xunit;

namespace Tenshu.TrayAgent.Tests;

public class HeartbeatTests
{
    /// <summary>
    /// Control parses this with <c>TrayLiveness.Heartbeat.parse</c>, which treats anything else as "no heartbeat" —
    /// and replaces the agent. The shape is the contract; the two sides are in different languages.
    /// </summary>
    [Fact]
    public void LineMatchesWhatTrayLivenessParses()
    {
        var at = DateTimeOffset.FromUnixTimeMilliseconds(1_788_352_944_806);
        Assert.Equal("1788352944806 visible\n", Heartbeat.Line(true, at));
        Assert.Equal("1788352944806 hidden\n", Heartbeat.Line(false, at));
    }

    /// <summary>Shared names and cadence with the macOS agent and TrayLiveness.</summary>
    [Fact]
    public void SharesTheMacOsFileNameAndInterval()
    {
        Assert.Equal("tray-agent.state", Heartbeat.FileName);
        Assert.Equal(TimeSpan.FromSeconds(10), Heartbeat.Interval);
    }

    [Fact]
    public void PublishWritesAtomicallyIntoTheDirectory()
    {
        var dir = Directory.CreateTempSubdirectory("tenshu-hb").FullName;
        try
        {
            var at = DateTimeOffset.FromUnixTimeMilliseconds(42);
            Heartbeat.Publish(dir, visible: true, at);
            Assert.Equal("42 visible\n", File.ReadAllText(Path.Combine(dir, Heartbeat.FileName)));
            Assert.False(File.Exists(Path.Combine(dir, Heartbeat.FileName + ".tmp")));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }
}

public class StringsTests
{
    public static TheoryData<string> Languages => new() { "en", "de" };

    /// <summary>The record makes a missing key a compile error; this catches an empty one, and a lost placeholder.</summary>
    [Theory]
    [MemberData(nameof(Languages))]
    public void EveryStringIsFilledAndKeepsTheEnglishPlaceholders(string language)
    {
        var english = Strings.ForLanguage("en");
        var strings = Strings.ForLanguage(language);
        foreach (var property in typeof(Strings).GetProperties(BindingFlags.Public | BindingFlags.Instance))
        {
            if (property.PropertyType != typeof(string)) continue;
            var value = (string)property.GetValue(strings)!;
            Assert.False(string.IsNullOrWhiteSpace(value), $"{language}.{property.Name} is empty");
            Assert.Equal(Placeholders((string)property.GetValue(english)!), Placeholders(value));
        }
    }

    private static string[] Placeholders(string s) =>
        Regex.Matches(s, @"\{\d+\}").Select(m => m.Value).Order().ToArray();
}
