import CarPlay
import UIKit
import NekoApp

/// Apple CarPlay 音频应用代理：提供「推荐 / 歌单 / 队列」三标签页 + 原生 Now Playing 界面（含喜欢按钮与 Up Next 队列）
class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private var homeTab: CPListTemplate?
    private var playlistsTab: CPListTemplate?
    private var queueTab: CPListTemplate?
    private let imageCache = NSCache<NSString, UIImage>()

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        CarPlayBridge.shared.ensureReady()

        let home = CPListTemplate(title: "推荐", sections: buildHomeSections())
        home.tabImage = UIImage(systemName: "music.note.house")
        home.emptyViewTitleVariants = ["暂无推荐"]
        home.emptyViewSubtitleVariants = ["在手机上登录或播放几首歌即可显示"]

        let playlists = CPListTemplate(title: "歌单", sections: buildPlaylistSections())
        playlists.tabImage = UIImage(systemName: "music.note.list")
        playlists.emptyViewTitleVariants = ["暂无歌单"]
        playlists.emptyViewSubtitleVariants = ["在手机上登录账号或创建本地歌单"]

        let queue = CPListTemplate(title: "队列", sections: buildQueueSections())
        queue.tabImage = UIImage(systemName: "list.bullet")
        queue.emptyViewTitleVariants = ["播放队列为空"]
        queue.emptyViewSubtitleVariants = ["从推荐或歌单选一首歌开始播放"]

        self.homeTab = home
        self.playlistsTab = playlists
        self.queueTab = queue

        configureNowPlaying()

        let tabBar = CPTabBarTemplate(templates: [home, playlists, queue])
        interfaceController.setRootTemplate(tabBar, animated: false, completion: nil)

        CarPlayBridge.shared.startObserving { [weak self] in
            self?.refreshAll()
        }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        CarPlayBridge.shared.stopObserving()
        self.interfaceController = nil
        self.homeTab = nil
        self.playlistsTab = nil
        self.queueTab = nil
    }

    private func refreshAll() {
        homeTab?.updateSections(buildHomeSections())
        playlistsTab?.updateSections(buildPlaylistSections())
        queueTab?.updateSections(buildQueueSections())
        updateNowPlayingButtons()
    }

    private func configureNowPlaying() {
        let np = CPNowPlayingTemplate.shared
        np.isUpNextButtonEnabled = true
        np.isAlbumArtistButtonEnabled = false
        updateNowPlayingButtons()
    }

    private func updateNowPlayingButtons() {
        let liked = CarPlayBridge.shared.isCurrentLiked()
        let icon = UIImage(systemName: liked ? "heart.fill" : "heart") ?? UIImage()
        let likeBtn = CPNowPlayingImageButton(image: icon) { [weak self] _ in
            CarPlayBridge.shared.toggleLikeCurrent()
            self?.updateNowPlayingButtons()
        }
        CPNowPlayingTemplate.shared.updateNowPlayingButtons([likeBtn])
    }

    private func showNowPlaying() {
        guard let ic = interfaceController else { return }
        if ic.topTemplate !== CPNowPlayingTemplate.shared {
            ic.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
        }
    }

    // MARK: - 1. 推荐 / 最近播放
    private func buildHomeSections() -> [CPListSection] {
        var sections: [CPListSection] = []

        let fmItem = CPListItem(text: "私人 FM", detailText: "按口味无限连播", image: UIImage(systemName: "radio"))
        fmItem.handler = { [weak self] _, done in
            CarPlayBridge.shared.startFm()
            self?.showNowPlaying()
            done()
        }

        let dailyItem = CPListItem(text: "每日推荐", detailText: "为你挑选的今日歌单", image: UIImage(systemName: "sparkles"))
        dailyItem.accessoryType = .disclosureIndicator
        dailyItem.handler = { [weak self] _, done in
            self?.openDailyTemplate()
            done()
        }

        let npItem = CPListItem(text: "正在播放", detailText: CarPlayBridge.shared.isPlaying() ? "查看当前播放界面" : "当前未在播放", image: UIImage(systemName: "play.circle"))
        npItem.accessoryType = .disclosureIndicator
        npItem.handler = { [weak self] _, done in
            self?.showNowPlaying()
            done()
        }

        sections.append(CPListSection(items: [fmItem, dailyItem, npItem], header: "快捷入口", sectionIndexTitle: nil))

        let recents = CarPlayBridge.shared.recents()
        if !recents.isEmpty {
            let curId = CarPlayBridge.shared.currentTrackId()
            let items = recents.enumerated().map { (idx, t) -> CPListItem in
                let item = CPListItem(text: t.title, detailText: t.artist)
                item.isPlaying = (t.id == curId)
                loadArtwork(urlString: t.coverUrl, into: item)
                item.handler = { [weak self] _, done in
                    CarPlayBridge.shared.playRecents(index: Int32(idx))
                    self?.showNowPlaying()
                    done()
                }
                return item
            }
            sections.append(CPListSection(items: items, header: "最近播放", sectionIndexTitle: nil))
        }
        return sections
    }

    // MARK: - 2. 歌单
    private func buildPlaylistSections() -> [CPListSection] {
        let lists = CarPlayBridge.shared.playlists()
        if lists.isEmpty { return [] }
        let items = lists.map { pl -> CPListItem in
            let sub = pl.isLocal ? "本地 · \(pl.count) 首" : "\(pl.count) 首"
            let item = CPListItem(text: pl.name, detailText: sub)
            item.accessoryType = .disclosureIndicator
            loadArtwork(urlString: pl.coverUrl, into: item)
            item.handler = { [weak self] _, done in
                self?.openPlaylistTemplate(pl)
                done()
            }
            return item
        }
        return [CPListSection(items: items)]
    }

    // MARK: - 3. 播放队列
    private func buildQueueSections() -> [CPListSection] {
        let q = CarPlayBridge.shared.queue()
        if q.isEmpty { return [] }
        let curIdx = Int(CarPlayBridge.shared.currentIndex())
        let items = q.enumerated().map { (idx, t) -> CPListItem in
            let item = CPListItem(text: t.title, detailText: t.artist)
            item.isPlaying = (idx == curIdx)
            loadArtwork(urlString: t.coverUrl, into: item)
            item.handler = { [weak self] _, done in
                CarPlayBridge.shared.playQueueAt(index: Int32(idx))
                self?.showNowPlaying()
                done()
            }
            return item
        }
        return [CPListSection(items: items, header: "当前队列 · \(q.count) 首", sectionIndexTitle: nil)]
    }

    // MARK: - 子页面：歌单曲目 / 每日推荐
    private func openPlaylistTemplate(_ pl: CarPlayPlaylist) {
        let loading = CPListItem(text: "正在加载…", detailText: nil)
        let listTpl = CPListTemplate(title: pl.name, sections: [CPListSection(items: [loading])])
        interfaceController?.pushTemplate(listTpl, animated: true, completion: nil)

        CarPlayBridge.shared.loadPlaylistTracks(playlistId: pl.id) { [weak self, weak listTpl] tracks in
            guard let self = self, let tpl = listTpl else { return }
            let curId = CarPlayBridge.shared.currentTrackId()
            let items = tracks.enumerated().map { (idx, t) -> CPListItem in
                let item = CPListItem(text: t.title, detailText: t.artist)
                item.isPlaying = (t.id == curId)
                self.loadArtwork(urlString: t.coverUrl, into: item)
                item.handler = { [weak self] _, done in
                    CarPlayBridge.shared.playLoadedList(listKey: pl.id, index: Int32(idx))
                    self?.showNowPlaying()
                    done()
                }
                return item
            }
            tpl.updateSections(items.isEmpty ? [] : [CPListSection(items: items)])
        }
    }

    private func openDailyTemplate() {
        let loading = CPListItem(text: "正在加载每日推荐…", detailText: nil)
        let listTpl = CPListTemplate(title: "每日推荐", sections: [CPListSection(items: [loading])])
        interfaceController?.pushTemplate(listTpl, animated: true, completion: nil)

        CarPlayBridge.shared.loadDailyTracks { [weak self, weak listTpl] tracks in
            guard let self = self, let tpl = listTpl else { return }
            let curId = CarPlayBridge.shared.currentTrackId()
            let items = tracks.enumerated().map { (idx, t) -> CPListItem in
                let item = CPListItem(text: t.title, detailText: t.artist)
                item.isPlaying = (t.id == curId)
                self.loadArtwork(urlString: t.coverUrl, into: item)
                item.handler = { [weak self] _, done in
                    CarPlayBridge.shared.playLoadedList(listKey: "__daily__", index: Int32(idx))
                    self?.showNowPlaying()
                    done()
                }
                return item
            }
            tpl.updateSections(items.isEmpty ? [] : [CPListSection(items: items)])
        }
    }

    // MARK: - 封面异步加载与缓存
    private func loadArtwork(urlString: String?, into item: CPListItem) {
        guard let raw = urlString, !raw.isEmpty else { return }
        let key = raw as NSString
        if let cached = imageCache.object(forKey: key) {
            item.setImage(cached)
            return
        }
        guard let url = URL(string: raw) else { return }
        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        URLSession.shared.dataTask(with: req) { [weak self, weak item] data, _, _ in
            guard let data = data, let img = UIImage(data: data) else { return }
            self?.imageCache.setObject(img, forKey: key)
            DispatchQueue.main.async { item?.setImage(img) }
        }.resume()
    }
}
