using System.Text.Json.Nodes;
using Tenshu.TrayAgent;
using Xunit;

namespace Tenshu.TrayAgent.Tests;

/// <summary>
/// The flyout decodes the daemon's replies; these pin the mapping from codes to copy. Assertions read the expected
/// copy from <see cref="Strings.Current"/>, so they hold under any display language.
/// </summary>
public class TrayStateTests
{
    private static Strings S => Strings.Current;

    private static TrayState From(string syncJson, string? requests = null, string? notifications = null) =>
        TrayState.From(
            JsonNode.Parse(syncJson)!.AsObject(),
            requests == null ? null : JsonNode.Parse(requests)!.AsObject(),
            notifications == null ? null : JsonNode.Parse(notifications)!.AsObject(),
            []);

    [Theory]
    [InlineData("SYNCED", false)]
    [InlineData("SYNCING", false)]
    [InlineData("OFFLINE", true)]
    [InlineData("DIVERGENCE", true)]
    [InlineData("NEEDS_UPDATE", true)]
    [InlineData("PAUSED", false)]
    [InlineData("LOCKED", false)]
    [InlineData("DISABLED", false)]
    public void EveryStateCodeHasItsOwnLabel(string code, bool warning)
    {
        var state = From($$"""{"state":"{{code}}"}""");
        Assert.True(state.Reachable);
        Assert.NotEqual(S.Unknown, state.Label);
        Assert.Equal(warning, state.Warning);
    }

    /// <summary>A code the daemon does not send ("ACTIVE") must read as Unknown, not pass through as raw text.</summary>
    [Fact]
    public void AnUnknownCodeIsNotShownRaw()
    {
        Assert.Equal(S.Unknown, From("""{"state":"ACTIVE"}""").Label);
    }

    [Fact]
    public void DivergenceDetailFallsBackFromNameToShortIdToAGenericNoun()
    {
        var named = From("""{"state":"DIVERGENCE","divergenceWarning":true,"divergencePeerName":"Pixel","divergencePeerDeviceId":"e4a23211-aaaa"}""");
        Assert.Equal(string.Format(S.ClockRegression, "Pixel", ""), named.Detail);

        var byId = From("""{"state":"SYNCED","divergenceWarning":true,"divergencePeerDeviceId":"e4a23211-aaaa","divergenceKind":"SIGNATURE_MISMATCH"}""");
        Assert.Equal(string.Format(S.SignatureMismatch, "e4a23211…", ""), byId.Detail);

        var anonymous = From("""{"state":"SYNCED","divergenceWarning":true}""");
        Assert.Equal(string.Format(S.ClockRegression, S.AnotherDevice, ""), anonymous.Detail);
    }

    /// <summary>Keyed off the raw warning: an aged-out one leaves the headline but keeps its detail line.</summary>
    [Fact]
    public void AStaleWarningKeepsItsDetail()
    {
        var state = From("""{"state":"SYNCED","divergenceWarning":true,"divergenceFresh":false}""");
        Assert.Equal(S.Synced, state.Label);
        Assert.NotNull(state.Detail);
    }

    [Fact]
    public void NoWarningMeansNoDetail()
    {
        Assert.Null(From("""{"state":"SYNCED","divergenceWarning":false}""").Detail);
    }

    [Fact]
    public void RequestsAreTitledByKindOnly()
    {
        var state = From(
            """{"state":"SYNCED"}""",
            requests: """{"requests":[{"id":"r1","kind":"membership_grant"},{"id":"r2","kind":"something_new"}]}""");
        Assert.Equal([S.RequestMembershipGrant, S.RequestDefault], state.Requests);
    }

    [Fact]
    public void NotificationsComposeTheirText()
    {
        var state = From(
            """{"state":"SYNCED"}""",
            notifications: """{"notifications":[{"id":"1","kind":"files_added","name":"a.jpg","otherCount":3},{"id":"2","kind":"app_registered","name":"Drive"}]}""");
        Assert.Equal([string.Format(S.FilesAdded, "a.jpg", 3), string.Format(S.AppRegistered, "Drive")], state.Notifications);
    }

    [Fact]
    public void MissingSectionsAreEmpty()
    {
        var state = From("""{"state":"SYNCED","paused":true,"enabled":true}""");
        Assert.Empty(state.Requests);
        Assert.Empty(state.Notifications);
        Assert.True(state.Paused);
        Assert.True(state.Enabled);
    }
}
