package fixture

fun passCommentsAndStrings() {
    val text = "invokeOnClose awaitClose // /* not code */"
    val escaped = "awaitClose: \"invokeOnClose\""
    val raw = """awaitClose invokeOnClose /* not code */"""
    val character = '\''
    /* outer comment /* nested invokeOnClose */ awaitClose */
    callbackFlow {
        send(text)
    }
}
