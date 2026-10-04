import { useState, useEffect } from 'react'
import { docsData } from './docsData'

export default function App() {
  const [currentPath, setCurrentPath] = useState<string>(() => {
    if (typeof window !== 'undefined') {
      if (window.location.pathname.startsWith('/docs') || window.location.hash.startsWith('#docs')) {
        return '/docs'
      }
    }
    return '/'
  })

  const [selectedDocId, setSelectedDocId] = useState<string>('install-windows')
  const [searchQuery, setSearchQuery] = useState<string>('')

  useEffect(() => {
    const handlePopState = () => {
      if (window.location.pathname.startsWith('/docs') || window.location.hash.startsWith('#docs')) {
        setCurrentPath('/docs')
        const hash = window.location.hash.replace('#docs/', '').replace('#docs', '')
        if (hash) {
          const match = docsData.find((d) => d.id === hash)
          if (match) setSelectedDocId(match.id)
        }
      } else {
        setCurrentPath('/')
      }
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  const navigateTo = (path: string, docId?: string) => {
    setCurrentPath(path)
    if (docId) setSelectedDocId(docId)
    if (path === '/docs') {
      const newHash = docId ? `#docs/${docId}` : '#docs'
      window.history.pushState(null, '', newHash)
    } else {
      window.history.pushState(null, '', '/')
    }
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const selectedDoc = docsData.find((d) => d.id === selectedDocId) || docsData[0]

  const filteredDocs = docsData.filter(
    (d) =>
      d.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
      d.content.toLowerCase().includes(searchQuery.toLowerCase())
  )

  const userGuides = filteredDocs.filter((d) => d.category === 'User Guides')
  const techDocs = filteredDocs.filter((d) => d.category === 'Technical Reference')

  return (
    <div className="app-container">
      {/* Navigation Bar */}
      <header className="navbar">
        <div className="nav-brand" onClick={() => navigateTo('/')}>
          <svg className="owl-logo" viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="1.8">
            <circle cx="12" cy="12" r="9" />
            <circle cx="9" cy="10" r="1.5" fill="currentColor" />
            <circle cx="15" cy="10" r="1.5" fill="currentColor" />
            <path d="M12 13l-1.5 2h3z" fill="currentColor" />
            <path d="M7 4L9 6M17 4L15 6" />
          </svg>
          <span className="brand-name">owlmic</span>
          <span className="version-tag">v1.0.0</span>
        </div>

        <nav className="nav-links">
          <button className={`nav-link ${currentPath === '/' ? 'active' : ''}`} onClick={() => navigateTo('/')}>
            Home
          </button>
          <button className={`nav-link ${currentPath === '/docs' ? 'active' : ''}`} onClick={() => navigateTo('/docs')}>
            Docs
          </button>
          <a
            href="https://github.com/diveshpatil9104/owlmic/releases"
            className="nav-link"
            target="_blank"
            rel="noreferrer"
          >
            Downloads
          </a>
          <a
            href="https://github.com/diveshpatil9104/owlmic"
            className="nav-btn"
            target="_blank"
            rel="noreferrer"
          >
            GitHub
          </a>
        </nav>
      </header>

      {/* Main View: Landing or Docs */}
      {currentPath === '/docs' ? (
        <main className="docs-layout">
          <aside className="docs-sidebar">
            <div className="search-box">
              <input
                type="text"
                placeholder="Search documentation..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>

            <div className="sidebar-group">
              <div className="group-title">User Guides</div>
              {userGuides.map((doc) => (
                <button
                  key={doc.id}
                  className={`doc-nav-item ${selectedDocId === doc.id ? 'active' : ''}`}
                  onClick={() => navigateTo('/docs', doc.id)}
                >
                  {doc.title}
                </button>
              ))}
            </div>

            <div className="sidebar-group">
              <div className="group-title">Technical Reference</div>
              {techDocs.map((doc) => (
                <button
                  key={doc.id}
                  className={`doc-nav-item ${selectedDocId === doc.id ? 'active' : ''}`}
                  onClick={() => navigateTo('/docs', doc.id)}
                >
                  {doc.title}
                </button>
              ))}
            </div>
          </aside>

          <article className="docs-content">
            <div className="doc-category-badge">{selectedDoc.category}</div>
            <div className="markdown-render">
              {selectedDoc.content.split('\n\n').map((block, i) => {
                if (block.startsWith('# ')) {
                  return <h1 key={i}>{block.replace('# ', '')}</h1>
                }
                if (block.startsWith('### ')) {
                  return <h3 key={i}>{block.replace('### ', '')}</h3>
                }
                if (block.startsWith('## ')) {
                  return <h2 key={i}>{block.replace('## ', '')}</h2>
                }
                if (block.startsWith('```')) {
                  const lines = block.split('\n')
                  const code = lines.slice(1, -1).join('\n')
                  return (
                    <pre key={i} className="code-block">
                      <code>{code}</code>
                    </pre>
                  )
                }
                if (block.startsWith('- ')) {
                  const items = block.split('\n')
                  return (
                    <ul key={i}>
                      {items.map((item, j) => (
                        <li key={j}>{item.replace(/^- /, '')}</li>
                      ))}
                    </ul>
                  )
                }
                if (block.startsWith('1. ')) {
                  const items = block.split('\n')
                  return (
                    <ol key={i}>
                      {items.map((item, j) => (
                        <li key={j}>{item.replace(/^\d+\. /, '')}</li>
                      ))}
                    </ol>
                  )
                }
                return <p key={i}>{block}</p>
              })}
            </div>
          </article>
        </main>
      ) : (
        <main className="home-layout">
          {/* Hero Section */}
          <section className="hero">
            <div className="hero-pill">
              <span className="pill-dot"></span>
              <span>v1.0.0 Production Release Now Available</span>
            </div>
            <h1 className="hero-title">
              Your phone is your PC's<br />
              <span className="accent-text">mic, camera and speaker.</span>
            </h1>
            <p className="hero-subtitle">
              Fast. Quick. Lightweight. Seamless. Connects in under a second over four automatic connection levels with zero cloud, zero latency, and zero bloat.
            </p>

            <div className="hero-cta">
              <a
                href="https://github.com/diveshpatil9104/owlmic/releases/download/v1.0.0/Owlmic-Setup-1.0.0.exe"
                className="btn primary-btn"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
                </svg>
                Download for Windows
                <span className="btn-subtext">~4 MB · Win 10 & 11</span>
              </a>

              <a
                href="https://github.com/diveshpatil9104/owlmic/releases/download/v1.0.0/owlmic-1.0.0.apk"
                className="btn secondary-btn"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <rect x="5" y="2" width="14" height="20" rx="2" ry="2" />
                  <line x1="12" y1="18" x2="12.01" y2="18" />
                </svg>
                Download Android APK
                <span className="btn-subtext">Google Play & Sideload</span>
              </a>

              <button className="btn outline-btn" onClick={() => navigateTo('/docs')}>
                Read Documentation &rarr;
              </button>
            </div>
          </section>

          {/* Live System Preview Mockup */}
          <section className="preview-section">
            <div className="preview-container">
              <div className="mockup-phone">
                <div className="phone-screen">
                  <div className="phone-topbar">
                    <span>Owlmic</span>
                    <span className="status-live">🟢 USB Debugging</span>
                  </div>
                  <div className="phone-grid">
                    <div className="phone-card active">
                      <div className="card-header">
                        <span>🎙️ Mic</span>
                        <span className="status-text">ON</span>
                      </div>
                      <div className="audio-meter">
                        <div className="meter-bar bar-1"></div>
                        <div className="meter-bar bar-2"></div>
                        <div className="meter-bar bar-3"></div>
                        <div className="meter-bar bar-4"></div>
                        <div className="meter-bar bar-5"></div>
                      </div>
                      <span className="card-sub">48 kHz PCM · 10 ms</span>
                    </div>

                    <div className="phone-card active">
                      <div className="card-header">
                        <span>📷 Camera</span>
                        <span className="status-text">ON</span>
                      </div>
                      <div className="cam-badge">1080p · 60 fps</div>
                      <span className="card-sub">Back Lens · OpenGL</span>
                    </div>

                    <div className="phone-card">
                      <div className="card-header">
                        <span>🔊 Speaker</span>
                        <span className="status-text inactive">OFF</span>
                      </div>
                      <span className="card-sub">Phone Speaker / BT</span>
                    </div>
                  </div>
                </div>
              </div>

              <div className="mockup-pc">
                <div className="pc-flyout">
                  <div className="flyout-header">
                    <div className="flyout-title">
                      <span className="owl-dot"></span>
                      <span>Desktop-PC · Pixel 8</span>
                    </div>
                    <span className="flyout-badge">Connected</span>
                  </div>
                  <div className="flyout-body">
                    <div className="tile mic-tile">
                      <div className="tile-title">Owlmic Mic</div>
                      <div className="tile-sub">In use by Discord & Teams</div>
                      <div className="tile-metric">Latency: 7 ms · Loss: 0.0%</div>
                    </div>
                    <div className="tile cam-tile">
                      <div className="tile-title">Owlmic Cam</div>
                      <div className="preview-box">
                        <span>Live Preview (10 FPS)</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </section>

          {/* Features Grid */}
          <section className="features-section">
            <h2 className="section-title">Built for Performance & Privacy</h2>
            <div className="features-grid">
              <div className="feature-card">
                <div className="feature-icon">🎙️</div>
                <h3>Studio Sound Quality</h3>
                <p>
                  48 kHz 16-bit uncompressed PCM on USB cable, or Opus with in-band FEC over Wi-Fi. Features an adaptive jitter buffer, ±0.2% cubic drift resampler, and optional neural RNNoise suppression.
                </p>
              </div>

              <div className="feature-card">
                <div className="feature-icon">📷</div>
                <h3>1080p60 Low-Latency Video</h3>
                <p>
                  Hardware-accelerated CameraX capture with OpenGL texture processing. Native Windows 11 Media Foundation software camera driver (<code>owlmic_vcam.dll</code>) and DirectShow fallback.
                </p>
              </div>

              <div className="feature-card">
                <div className="feature-icon">⚡</div>
                <h3>Make-Before-Break Switching</h3>
                <p>
                  Move between USB debugging, USB tethering, Wi-Fi, and Bluetooth without dropping a call. Connections migrate instantly the moment you plug or unplug a cable.
                </p>
              </div>

              <div className="feature-card">
                <div className="feature-icon">🛡️</div>
                <h3>100% Peer-to-Peer Privacy</h3>
                <p>
                  Zero cloud servers, zero telemetry, zero accounts. Secured with NIST P-256 elliptic-curve keys, 4-digit code verification, and AES-256-GCM wireless encryption.
                </p>
              </div>

              <div className="feature-card">
                <div className="feature-icon">🔊</div>
                <h3>Remote Speaker Loopback</h3>
                <p>
                  Stream Windows audio back to your phone speaker or headphones with optional Quiet PC Speakers muting so you can walk around your room and still hear meeting audio.
                </p>
              </div>

              <div className="feature-card">
                <div className="feature-icon">🪶</div>
                <h3>Sub-15ms Native Win32 Tray</h3>
                <p>
                  Zero Electron, zero webviews. The tray flyout opens instantly using native double-buffered GDI drawing with an idle footprint under 15 MB RAM.
                </p>
              </div>
            </div>
          </section>

          {/* Connection Levels Table */}
          <section className="levels-section">
            <h2 className="section-title">Four Seamless Connection Levels</h2>
            <div className="table-wrapper">
              <table className="levels-table">
                <thead>
                  <tr>
                    <th>Priority</th>
                    <th>Level</th>
                    <th>Latency</th>
                    <th>Audio Codec</th>
                    <th>Video Capability</th>
                    <th>Encryption</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td><span className="prio-badge prio-1">1</span></td>
                    <td><strong>USB Debugging</strong></td>
                    <td>&lt; 10 ms</td>
                    <td>Uncompressed 48 kHz PCM</td>
                    <td>Up to 1080p60 H.264</td>
                    <td>Direct Hardware Bus</td>
                  </tr>
                  <tr>
                    <td><span className="prio-badge prio-2">2</span></td>
                    <td><strong>USB Tethering</strong></td>
                    <td>&lt; 15 ms</td>
                    <td>Uncompressed 48 kHz PCM</td>
                    <td>Up to 1080p60 H.264</td>
                    <td>Direct Hardware Bus</td>
                  </tr>
                  <tr>
                    <td><span className="prio-badge prio-3">3</span></td>
                    <td><strong>Wi-Fi LAN</strong></td>
                    <td>&lt; 25 ms</td>
                    <td>Opus (48 kbps) with FEC</td>
                    <td>Up to 1080p H.264</td>
                    <td>AES-256-GCM</td>
                  </tr>
                  <tr>
                    <td><span className="prio-badge prio-4">4</span></td>
                    <td><strong>Bluetooth RFCOMM</strong></td>
                    <td>~45 ms</td>
                    <td>Opus (24 kbps)</td>
                    <td>Audio Only</td>
                    <td>AES-256-GCM</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </main>
      )}

      {/* Footer */}
      <footer className="footer">
        <div className="footer-content">
          <div className="footer-left">
            <span className="footer-logo">owlmic</span>
            <p>Fast. Quick. Lightweight. Seamless.</p>
          </div>
          <div className="footer-links">
            <button className="footer-link-btn" onClick={() => navigateTo('/')}>Home</button>
            <button className="footer-link-btn" onClick={() => navigateTo('/docs')}>Documentation</button>
            <a href="https://github.com/diveshpatil9104/owlmic/releases" target="_blank" rel="noreferrer">Releases</a>
            <a href="https://github.com/diveshpatil9104/owlmic" target="_blank" rel="noreferrer">GitHub</a>
            <a href="https://github.com/diveshpatil9104/owlmic/blob/main/LICENSE" target="_blank" rel="noreferrer">MIT License</a>
          </div>
        </div>
      </footer>
    </div>
  )
}
