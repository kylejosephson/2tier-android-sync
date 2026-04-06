package com.example.kylesmusicplayerandroid.domain.model

/**
 * Canonical, normalized identity for comparing songs across systems.
 *
 * IMPORTANT:
 * - Sync + comparison ONLY
 * - No MediaStore IDs
 * - No persistence
 */
data class NormalizedSongKey(
    val artist: String,
    val album: String,
    val title: String
) {

    override fun toString(): String =
        "$artist | $album | $title"

    companion object {

        fun fromFilesystem(
            artist: String?,
            album: String?,
            filename: String
        ): NormalizedSongKey {

            val a = normalizeArtist(artist)
            val al = normalizeAlbum(album)

            val t = normalizeFilesystemTitle(
                filename = filename,
                normalizedArtist = a
            )

            return NormalizedSongKey(
                artist = a,
                album = al,
                title = t
            )
        }

        // ---------------------------------------------------------
        // Album normalization
        // ---------------------------------------------------------

        private fun normalizeAlbum(value: String?): String {
            return normalizeBase(value)
                .normalizeAlbumPunctuation()
                .stripParentheticals()
        }

        // ---------------------------------------------------------
        // Filesystem title normalization (OneDrive + SAF)
        // ---------------------------------------------------------

        private fun normalizeFilesystemTitle(
            filename: String,
            normalizedArtist: String
        ): String {

            // 1) strip extension (supports names without extension too)
            var base = filename.substringBeforeLast('.', filename)

            // 2) normalize to our base form early (lowercase, quotes, whitespace)
            base = normalizeBase(base)

            // 3) normalize separators
            base = base
                .replace('_', ' ')
                .replace("–", "-")
                .replace("—", "-")
                .replace(":", " ")
                .replace(Regex("\\s*-\\s*"), " - ")

            // 4) strip leading disc markers: "(disc 2) 01 title" -> "01 title"
            base = base.replace(
                Regex("^\\s*\\(\\s*disc\\s*\\d+\\s*\\)\\s*", RegexOption.IGNORE_CASE),
                ""
            )

            // 5) remove leading track numbers like:
            // "04 Rush - War Paint", "04 - War Paint", "04. War Paint", "04_War Paint", AND "04 War Paint"
            base = base.replace(
                Regex("^\\s*\\d{1,3}\\s*([\\-._]|\\s)+\\s*"),
                ""
            )

            // 6) remove leftover leading junk dashes after track stripping
            base = base.replace(Regex("^\\s*[-–—]+\\s*"), "")

            // 7) remove artist prefix embedded in filename
            if (normalizedArtist.isNotBlank()) {
                val escaped = Regex.escape(normalizedArtist)
                base = base.replace(
                    Regex("^\\s*$escaped\\s*([\\-–—_:]|\\s)+\\s*"),
                    ""
                )
            }

            // 8) treat remaining hyphens as spaces in title
            base = base.replace('-', ' ')

            // 9) strip trailing version tags like "(remaster)" "[explicit]" at the end ONLY if clearly tags
            base = stripTrailingVersionTags(base)

            // 10) final cleanup
            base = base
                .collapseWhitespace()
                .trim()

            // Safety net: never allow empty title
            if (base.isBlank()) {
                base = normalizeBase(filename.substringBeforeLast('.', filename)).trim()
            }

            return base
        }

        private fun stripTrailingVersionTags(value: String): String {
            var v = value

            while (true) {
                val m = Regex("\\s*(\\(|\\[)([^\\]\\)]{1,80})(\\)|\\])\\s*$").find(v) ?: break
                val inside = m.groupValues[2].trim()

                val looksLikeTag = inside.contains(
                    Regex(
                        "(remaster|remastered|version|edit|mix|explicit|bonus|demo|acoustic|radio|single|soundtrack|live|mono|stereo|instrumental|commentary|deluxe|feat\\b|featuring\\b|with\\b)",
                        RegexOption.IGNORE_CASE
                    )
                )

                if (!looksLikeTag) break
                v = v.substring(0, m.range.first).trimEnd()
            }

            return v
        }

        // ---------------------------------------------------------
        // Shared normalization base
        // ---------------------------------------------------------

        private fun normalizeArtist(value: String?): String {
            val v = normalizeBase(value)
            return if (v == "<unknown>") "" else v
        }

        private fun normalizeBase(value: String?): String {
            if (value.isNullOrBlank()) return ""

            return value
                .trim()
                .lowercase()
                .fixBrokenUtf8Quotes()
                .replaceSmartQuotes()
                .collapseWhitespace()
        }

        // ---------------------------------------------------------
        // Helpers
        // ---------------------------------------------------------

        private fun String.fixBrokenUtf8Quotes(): String =
            this
                .replace("â€™", "'")
                .replace("â€˜", "'")
                .replace("â€œ", "\"")
                .replace("â€�", "\"")

        private fun String.replaceSmartQuotes(): String =
            this
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"')

        private fun String.collapseWhitespace(): String =
            this.replace(Regex("\\s+"), " ")

        private fun String.normalizeAlbumPunctuation(): String =
            this
                .replace(":", " ")
                .replace("–", "-")
                .replace("—", "-")

        private fun String.stripParentheticals(): String =
            this.replace(Regex("\\s*\\(.*?\\)"), "")
    }
}
