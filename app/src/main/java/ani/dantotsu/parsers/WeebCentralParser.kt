package ani.dantotsu.parsers

import ani.dantotsu.FileUrl
import ani.dantotsu.media.manga.ImageData
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.en.weebcentral.WeebCentral
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import rx.Observable
import eu.kanade.tachiyomi.util.lang.awaitSingle

class WeebCentralParser : MangaParser() {
    private val mangaCache = Injekt.get<MangaCache>()
    val source = WeebCentral()

    override val name = "Weeb Central (Inbuilt)"
    override val saveName = "Weeb Central (Inbuilt)"
    override val hostUrl = "https://weebcentral.com"
    override val isNSFW = false

    override suspend fun loadChapters(
        mangaLink: String,
        extra: Map<String, String>?,
        sManga: SManga
    ): List<MangaChapter> {
        return try {
            val res = source.getChapterList(sManga)
            val reversedRes = res.reversed()
            reversedRes.map { sChapterToMangaChapter(it) }
        } catch (e: Exception) {
            Logger.log("WeebCentralParser loadChapters Exception: $e")
            emptyList()
        }
    }

    override suspend fun loadImages(chapterLink: String, sChapter: SChapter): List<MangaImage> {
        val imageDataList: MutableList<ImageData> = mutableListOf()
        return coroutineScope {
            try {
                val res = source.getPageList(sChapter)
                val reIndexedPages = res.mapIndexed { index, page -> 
                    Page(index, page.url, page.imageUrl, page.uri) 
                }

                val deferreds = reIndexedPages.map { page ->
                    async(Dispatchers.IO) {
                        mangaCache.put(page.imageUrl ?: "", ImageData(page, source))
                        imageDataList += ImageData(page, source)
                        pageToMangaImage(page)
                    }
                }
                deferreds.awaitAll()
            } catch (e: Exception) {
                Logger.log("WeebCentralParser loadImages Exception: $e")
                withContext(Dispatchers.Main) {
                    snackString("Failed to load images from Weeb Central: $e")
                }
                emptyList()
            }
        }
    }

    override suspend fun search(query: String): List<ShowResponse> {
        return try {
            val res = source.fetchSearchManga(1, query, source.getFilterList()).awaitSingle()
            res.mangas.map { sManga ->
                ShowResponse(sManga.title, sManga.url, sManga.thumbnail_url ?: "", sManga)
            }
        } catch (e: Exception) {
            Logger.log("WeebCentralParser search Exception: $e")
            emptyList()
        }
    }

    private fun pageToMangaImage(page: Page): MangaImage {
        var headersMap = mapOf<String, String>()
        var url = ""

        page.imageUrl?.let {
            val splitUrl = it.split("&")
            url = it

            headersMap = splitUrl.mapNotNull { part ->
                val idx = part.indexOf("=")
                if (idx != -1) {
                    try {
                        val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                        val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                        Pair(key, value)
                    } catch (e: UnsupportedEncodingException) {
                        null
                    }
                } else {
                    null
                }
            }.toMap()
        }

        return MangaImage(
            FileUrl(url, headersMap),
            false,
            page
        )
    }

    private fun sChapterToMangaChapter(sChapter: SChapter): MangaChapter {
        return MangaChapter(
            sChapter.name,
            sChapter.url,
            sChapter.name,
            null,
            sChapter.scanlator ?: "Unknown",
            sChapter,
            sChapter.date_upload
        )
    }
}
