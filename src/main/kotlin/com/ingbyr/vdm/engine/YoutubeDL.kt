package com.ingbyr.vdm.engine

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.ingbyr.vdm.models.DownloadTaskModel
import com.ingbyr.vdm.utils.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tornadofx.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * TODO wrap download playlist
 */
class YoutubeDL : AbstractEngine() {
    override val logger: Logger = LoggerFactory.getLogger(this::class.java)
    override val remoteVersionUrl: String = "https://raw.githubusercontent.com/rg3/youtube-dl/master/youtube_dl/version.py"
    override val engineType = EngineType.YOUTUBE_DL
    override val enginePath: String = initEnginePath()
    override var remoteVersion: String? = null

    private var speed = "0MiB/s"
    private var progress = 0.0
    private var size = ""
    private var title = ""
    private val nameTemplate = "%(title)s.%(ext)s"
    private val progressPattern = Pattern.compile("\\d+\\W?\\d*%")
    private val speedPattern = Pattern.compile("\\d+\\W?\\d*\\w+/s")
    private val titlePattern = Pattern.compile("[/\\\\][^/^\\\\]+\\.\\w+")
    private val fileSizePattern = Pattern.compile("\\d+\\W?\\d*\\w+B")
    private val remoteVersionPattern = Pattern.compile("'\\d+.+'")
    private var taskModel: DownloadTaskModel? = null
    private lateinit var msg: ResourceBundle


    init {
        argsMap["engine"] = enginePath
    }

    private fun initEnginePath(): String {
        return when (VDMOSUtils.currentOS) {
            VDMOSType.WINDOWS -> {
                Paths.get(System.getProperty("user.dir"), "engine", "youtube-dl.exe").toAbsolutePath().toString()
            }
            VDMOSType.LINUX, VDMOSType.MAC_OS -> {
                Paths.get(System.getProperty("user.dir"), "engine", "youtube-dl").toAbsolutePath().toString()
            }
        }
    }

    override fun parseFormatsJson(json: JsonObject): List<MediaFormat> {
        val title = json.string("title") ?: ""
        val desc = json.string("description") ?: ""
        val formatsJson = json.array<JsonObject>("formats")
        val formats = mutableListOf<MediaFormat>()
        if (formatsJson != null && formatsJson.isNotEmpty()) {
            formatsJson.sortBy {
                it.string("format_id")
            }
            formatsJson.forEachIndexed { index, jsonObject ->
                formats.add(MediaFormat(
                        title = title,
                        desc = desc,
                        vdmTaskID = index,
                        formatID = jsonObject.string("format_id") ?: "",
                        format = jsonObject.string("format") ?: "",
                        formatNote = jsonObject.string("format_note") ?: "",
                        fileSize = jsonObject.long("filesize") ?: 0,
                        ext = jsonObject.string("ext") ?: ""
                ))
            }
        }
        return formats
    }

    override fun url(url: String): AbstractEngine {
        argsMap["url"] = url
        return this
    }

    override fun addProxy(proxy: VDMProxy): AbstractEngine {
        when (proxy.proxyType) {
            ProxyType.SOCKS5 -> {
                if (proxy.address.isEmpty() or proxy.port.isEmpty()) {
                    logger.debug("add an empty proxy to youtube-dl")
                    return this
                } else {
                    argsMap["--proxy"] = "socks5://${proxy.address}:${proxy.port}"
                }
            }

            ProxyType.HTTP -> {
                if (proxy.address.isEmpty() or proxy.port.isEmpty()) {
                    logger.debug("add an empty proxy to youtube-dl")
                    return this
                } else {
                    argsMap["--proxy"] = "${proxy.address}:${proxy.port}"
                }
            }

            else -> {
            }
        }
        return this
    }

    override fun fetchMediaJson(): JsonObject {
        argsMap["SimulateJson"] = "-j"
        val mediaData = execCommand(argsMap.build(), DownloadType.JSON)
        if (mediaData != null) {
            try {
                return Parser().parse(mediaData) as JsonObject
            } catch (e: Exception) {
                logger.error(e.toString())
                throw DownloadEngineException("parse data failed:\n $mediaData")
            }
        } else {
            logger.error("no media json return")
            throw DownloadEngineException("no media json return")
        }
    }

    override fun format(formatID: String): AbstractEngine {
        return if (formatID.isEmpty()) {
            this
        } else {
            argsMap["-f"] = formatID
            this
        }
    }

    override fun output(outputPath: String): AbstractEngine {
        argsMap["-o"] = Paths.get(outputPath, nameTemplate).toString()
        return this
    }

    override fun downloadMedia(downloadTaskModel: DownloadTaskModel, message: ResourceBundle) {
        taskModel = downloadTaskModel
        msg = message
        taskModel?.run {
            // init display
            execCommand(argsMap.build(), DownloadType.SINGLE)
        }
    }

    override fun parseDownloadOutput(line: String) {
        if (title.isEmpty()) {
            title = titlePattern.matcher(line).takeIf { it.find() }?.group()?.toString() ?: title
            title = title.removePrefix("/")
            if (title.isNotEmpty()) taskModel?.title = title
        }
        if (size.isEmpty()) {
            size = fileSizePattern.matcher(line).takeIf { it.find() }?.group()?.toString() ?: size
            if (size.isNotEmpty()) taskModel?.size = size
        }

        progress = progressPattern.matcher(line).takeIf { it.find() }?.group()?.toProgress() ?: progress
        speed = speedPattern.matcher(line).takeIf { it.find() }?.group()?.toString() ?: speed
        logger.debug("$line -> title=$title, progress=$progress, size=$size, speed=$speed")

        taskModel?.run {
            if (this@YoutubeDL.progress >= 1.0) {
                this.progress = 1.0
                if (line.trim().startsWith("[ffmpeg]"))
                    this.status = msg["ui.merging"]
                else
                    this.status = msg["ui.completed"]
            } else if (this@YoutubeDL.progress > 0) {
                this.progress = this@YoutubeDL.progress
                this.status = msg["ui.downloading"]
            }
        }
    }

    override fun execCommand(command: MutableList<String>, downloadType: DownloadType): StringBuilder? {
        /**
         * Exec the command by invoking the system shell etc.
         * Long time task
         */
        running.set(true)
        val builder = ProcessBuilder(command)
        builder.redirectErrorStream(true)
        val p = builder.start()
        val r = BufferedReader(InputStreamReader(p.inputStream))
        val output = StringBuilder()
        var line: String?
        when (downloadType) {
            DownloadType.JSON -> {
                // fetch the media json and return string builder
                while (running.get()) {
                    line = r.readLine()
                    if (line != null) {
                        output.append(line.trim())
                    } else {
                        break
                    }
                }
            }

            DownloadType.SINGLE, DownloadType.PLAYLIST -> {
                while (running.get()) {
                    line = r.readLine()
                    if (line != null) {
                        parseDownloadOutput(line)
                    } else {
                        break
                    }
                }
            }
        }

        if (p.isAlive) { // means user stop this task manually
            p.destroy()
            p.waitFor(200, TimeUnit.MICROSECONDS)
        }

        if (p.isAlive) {// can not destroy process
            p.destroyForcibly()
        }

        return if (running.get()) {
            running.set(false)
            output
        } else { // means user stop this task manually
            taskModel?.run {
                status = msg["ui.stopped"]
            }
            logger.debug("stop the task of $taskModel")
            null
        }
    }

    private fun String.toProgress(): Double {
        /**
         * Transfer "42.3%"(String) to 0.423(Double)
         */
        val s = this.replace("%", "")
        return s.trim().toDouble() / 100
    }

    private fun String.playlistIsCompleted(): Boolean {
        /**
         * Compare a / b and return the a>=b
         */
        val progress = this.split("/")
        return progress[0].trim() >= progress[1].trim()
    }

    override fun updateUrl() = when (VDMOSUtils.currentOS) {
        VDMOSType.WINDOWS -> {
            "https://github.com/rg3/youtube-dl/releases/download/$remoteVersion/youtube-dl.exe"
        }
        VDMOSType.LINUX, VDMOSType.MAC_OS -> {
            "https://github.com/rg3/youtube-dl/releases/download/$remoteVersion/youtube-dl"
        }
    }

    override fun existNewVersion(localVersion: String): Boolean {
        val remoteVersionInfo = NetUtils().get(remoteVersionUrl)
        return if (remoteVersionInfo?.isNotEmpty() == true) {
            remoteVersion = remoteVersionPattern.matcher(remoteVersionInfo).takeIf { it.find() }?.group()?.toString()?.replace("'", "")?.replace("\"", "")
            if (remoteVersion != null) {
                logger.debug("[$engineType] local version $localVersion, remote version $remoteVersion")
                VDMUtils.newVersion(localVersion, remoteVersion!!)
            } else {
                logger.error("[$engineType] get remote version failed")
                false
            }
        } else {
            logger.error("[$engineType] get remote version failed")
            false
        }
    }
}