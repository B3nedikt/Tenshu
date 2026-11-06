package app.tenshu.control.ui.screens.shares

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.data.ShareSummary
import app.tenshu.control.data.SharedFolderSummary
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SharesScreenContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun share(
        shareId: String,
        fileName: String,
        revoked: Boolean = false,
        maxOpens: Int? = null,
        expiresAtMillis: Long? = null,
    ) = ShareSummary(
        shareId = shareId,
        filePath = "/$fileName",
        fileName = fileName,
        createdAtMillis = 1_717_000_000_000L,
        expiresAtMillis = expiresAtMillis,
        maxOpens = maxOpens,
        opensSoFar = null,
        revoked = revoked,
    )

    @Test
    fun rendersRows_withFileNames() {
        composeTestRule.setContent {
            SharesScreenContent(
                uiState = SharesUiState(
                    shares = listOf(
                        share("a", "return.pdf", maxOpens = 5),
                        share("b", "beach.jpg"),
                    )
                ),
                onBack = {},
                onRevokeClicked = {},
                onRevokeConfirmed = {},
                onRevokeDismissed = {},
            )
        }
        composeTestRule.onNodeWithText("return.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("beach.jpg").assertIsDisplayed()
    }

    @Test
    fun revokedRow_showsBadge() {
        composeTestRule.setContent {
            SharesScreenContent(
                uiState = SharesUiState(shares = listOf(share("a", "old.txt", revoked = true))),
                onBack = {},
                onRevokeClicked = {},
                onRevokeConfirmed = {},
                onRevokeDismissed = {},
            )
        }
        composeTestRule.onNodeWithText("Revoked").assertIsDisplayed()
    }

    @Test
    fun revokeAction_firesCallbackWithShareId() {
        var clicked: String? = null
        composeTestRule.setContent {
            SharesScreenContent(
                uiState = SharesUiState(shares = listOf(share("share-42", "doc.pdf"))),
                onBack = {},
                onRevokeClicked = { clicked = it },
                onRevokeConfirmed = {},
                onRevokeDismissed = {},
            )
        }
        composeTestRule.onNodeWithContentDescription("Revoke share").performClick()
        assertEquals("share-42", clicked)
    }

    @Test
    fun emptyState_rendersWhenNothingShared() {
        composeTestRule.setContent {
            SharesScreenContent(
                uiState = SharesUiState(),
                onBack = {},
                onRevokeClicked = {},
                onRevokeConfirmed = {},
                onRevokeDismissed = {},
            )
        }
        composeTestRule.onNodeWithText("Nothing shared yet").assertIsDisplayed()
    }

    @Test
    fun sharedFoldersGroup_rendersOnlyWhenNonEmpty() {
        composeTestRule.setContent {
            SharesScreenContent(
                uiState = SharesUiState(
                    shares = listOf(share("a", "doc.pdf")),
                    sharedFolders = listOf(
                        SharedFolderSummary(
                            realmId = "realm-1",
                            name = "Family Photos",
                            memberCount = 3,
                            ownerSelf = true,
                        )
                    ),
                ),
                onBack = {},
                onRevokeClicked = {},
                onRevokeConfirmed = {},
                onRevokeDismissed = {},
            )
        }
        composeTestRule.onNodeWithText("Shared folders").assertIsDisplayed()
        composeTestRule.onNodeWithText("Family Photos").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 members · you own this").assertIsDisplayed()
    }

    @Test
    fun unsyncedWrapperRoot_rendersTheFallbackLabel_notABlankTitle() {
        // The joiner's first moments: the membership row is here, the wrapper-root
        // SharedFile that names the realm has not synced yet.
        composeTestRule.setContent {
            SharesScreenContent(
                uiState = SharesUiState(
                    sharedFolders = listOf(
                        SharedFolderSummary(
                            realmId = "realm-fresh",
                            name = null,
                            memberCount = 2,
                            ownerSelf = false,
                        )
                    ),
                ),
                onBack = {},
                onRevokeClicked = {},
                onRevokeConfirmed = {},
                onRevokeDismissed = {},
            )
        }
        composeTestRule.onNodeWithText("Shared folder").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 members").assertIsDisplayed()
    }

    @Test
    fun anUnreadableRoster_omitsTheCount_ratherThanClaimingZeroMembers() {
        // memberCount == null means the roster read failed. "0 members" would be a
        // wrong answer stated confidently, so the row says what it does know.
        composeTestRule.setContent {
            SharesScreenContent(
                uiState = SharesUiState(
                    sharedFolders = listOf(
                        SharedFolderSummary(
                            realmId = "realm-1",
                            name = "Family Photos",
                            memberCount = null,
                            ownerSelf = true,
                        )
                    ),
                ),
                onBack = {},
                onRevokeClicked = {},
                onRevokeConfirmed = {},
                onRevokeDismissed = {},
            )
        }
        composeTestRule.onNodeWithText("You own this").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 members").assertDoesNotExist()
    }

    @Test
    fun confirmDialog_rendersAndConfirms() {
        var confirms = 0
        composeTestRule.setContent {
            SharesScreenContent(
                uiState = SharesUiState(
                    shares = listOf(share("share-42", "doc.pdf")),
                    pendingRevokeShareId = "share-42",
                ),
                onBack = {},
                onRevokeClicked = {},
                onRevokeConfirmed = { confirms += 1 },
                onRevokeDismissed = {},
            )
        }
        // Dialog names the file and offers Revoke.
        composeTestRule.onNodeWithText("Revoke link?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Revoke").performClick()
        assertEquals(1, confirms)
    }
}
