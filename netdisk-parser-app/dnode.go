package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// ─────────────────────────────────────────────────────────────
//  内置 dnode 节点客户端（原 nfd proxy 功能，Go 重写）
//  帧协议：>IB = uint32 tid + uint8 type，payload 跟随
// ─────────────────────────────────────────────────────────────
const (
	frameConnectReq  = 0x01 // 服务端→节点：建隧道，payload=[host_len]host[port:2BE]
	frameConnectOK   = 0x02 // 节点→服务端：隧道连接成功
	frameConnectFail = 0x03 // 节点→服务端：隧道连接失败
	frameData        = 0x04 // 双向：TCP 数据块
	frameClose       = 0x05 // 双向：关闭隧道
)

const (
	dnodeServer   = "wss://dnode.qaiu.top/ws/node"
	dnodeMaxConns = 50
)

type dnodeTunnel struct {
	id   uint32
	conn net.Conn
	ch   chan []byte
	once sync.Once
}

func (t *dnodeTunnel) close() {
	t.once.Do(func() {
		close(t.ch)
		if t.conn != nil {
			_ = t.conn.Close()
		}
	})
}

type DNode struct {
	nodeID   string
	running  bool
	mu       sync.Mutex
	tunnels  map[uint32]*dnodeTunnel
	sendCh   chan []byte
	lastPong time.Time
}

func ensureNodeID(dir string) string {
	p := filepath.Join(dir, "node_id")
	if b, err := os.ReadFile(p); err == nil {
		id := strings.TrimSpace(string(b))
		if len(id) >= 8 {
			return id
		}
	}
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	id := hex.EncodeToString(b)
	_ = os.WriteFile(p, []byte(id), 0o644)
	return id
}

func localIP() string {
	for _, h := range []string{"8.8.8.8:80", "1.1.1.1:80", "114.114.114.114:80"} {
		c, err := net.DialTimeout("udp", h, time.Second)
		if err == nil {
			if a, ok := c.LocalAddr().(*net.UDPAddr); ok {
				_ = c.Close()
				return a.IP.String()
			}
			_ = c.Close()
		}
	}
	return "?"
}

func deviceFP(nodeID string) string {
	sum := sha256.Sum256([]byte("nfd:" + nodeID))
	return hex.EncodeToString(sum[:8])
}

func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

// startDNode 启动内置 dnode 节点（后台常驻，断线自动重连）
func startDNode(appDir string) {
	n := &DNode{
		nodeID:   ensureNodeID(appDir),
		tunnels:  make(map[uint32]*dnodeTunnel),
		sendCh:   make(chan []byte, 512),
		running:  true,
	}
	appLog("dnode节点: 启动 node_id=%s server=%s", shortID(n.nodeID), dnodeServer)
	go n.run()
}

func (n *DNode) run() {
	delays := []time.Duration{2, 4, 8, 16, 30, 60}
	attempt := 0
	for n.running {
		err := n.connectLoop()
		if !n.running {
			break
		}
		if err == nil {
			attempt = 0
		}
		d := delays[attempt]
		if attempt < len(delays)-1 {
			attempt++
		}
		appLog("dnode节点: 连接断开(%v)，%ds 后重连", err, int(d.Seconds()))
		time.Sleep(d * time.Second)
	}
}

func (n *DNode) connectLoop() error {
	hdr := http.Header{}
	hdr.Set("X-Node-ID", n.nodeID)
	hdr.Set("X-Platform", "Android")
	hdr.Set("X-Version", "3.0")
	hdr.Set("X-Device-FP", deviceFP(n.nodeID))
	hdr.Set("X-Local-IP", localIP())
	hdr.Set("X-Default-Node", "true")

	appLog("dnode节点: 连接服务端 url=%s node_id=%s", dnodeServer, shortID(n.nodeID))
	ws, _, err := websocket.DefaultDialer.Dial(dnodeServer, hdr)
	if err != nil {
		return err
	}
	appLog("dnode节点: 已连接 node_id=%s", shortID(n.nodeID))
	n.lastPong = time.Now()

	// gorilla 需手动回 pong（服务端 aiohttp heartbeat 会发 ping 控制帧）
	ws.SetPingHandler(func(appData string) error {
		_ = ws.WriteControl(websocket.PongMessage, []byte(appData), time.Now().Add(10*time.Second))
		return nil
	})
	ws.SetReadLimit(64 << 20)

	done := make(chan struct{})
	go func() {
		defer close(done)
		n.sender(ws)
	}()
	go n.pingLoop(ws)

	defer func() {
		// 关闭所有隧道
		n.mu.Lock()
		for _, t := range n.tunnels {
			t.close()
		}
		n.tunnels = make(map[uint32]*dnodeTunnel)
		n.mu.Unlock()
		_ = ws.Close()
	}()

	for {
		mt, data, err := ws.ReadMessage()
		if err != nil {
			break
		}
		if mt == websocket.BinaryMessage {
			n.dispatch(data)
		} else if mt == websocket.TextMessage {
			var m map[string]interface{}
			if json.Unmarshal(data, &m) == nil && m["type"] == "pong" {
				n.lastPong = time.Now()
			}
		}
	}
	<-done
	return nil
}

func (n *DNode) sender(ws *websocket.Conn) {
	for frame := range n.sendCh {
		if err := ws.WriteMessage(websocket.BinaryMessage, frame); err != nil {
			return
		}
	}
}

func (n *DNode) pingLoop(ws *websocket.Conn) {
	t := time.NewTicker(25 * time.Second)
	defer t.Stop()
	for range t.C {
		if time.Since(n.lastPong) > 70*time.Second {
			appLog("dnode节点: 70s 无 pong，关闭僵死连接")
			_ = ws.Close()
			return
		}
		msg, _ := json.Marshal(map[string]string{"type": "ping"})
		if err := ws.WriteMessage(websocket.TextMessage, msg); err != nil {
			return
		}
	}
}

func (n *DNode) sendFrame(tid uint32, ftype byte, payload []byte) {
	frame := make([]byte, 5+len(payload))
	binary.BigEndian.PutUint32(frame[:4], tid)
	frame[4] = ftype
	copy(frame[5:], payload)
	select {
	case n.sendCh <- frame:
	default:
	}
}

func (n *DNode) dispatch(raw []byte) {
	if len(raw) < 5 {
		return
	}
	tid := binary.BigEndian.Uint32(raw[:4])
	ftype := raw[4]
	payload := raw[5:]
	switch ftype {
	case frameConnectReq:
		if len(payload) < 3 {
			return
		}
		hl := int(payload[0])
		if len(payload) < 1+hl+2 {
			return
		}
		host := string(payload[1 : 1+hl])
		port := binary.BigEndian.Uint16(payload[1+hl : 3+hl])
		go n.runTunnel(tid, host, port)
	case frameData:
		n.mu.Lock()
		t := n.tunnels[tid]
		n.mu.Unlock()
		if t != nil {
			select {
			case t.ch <- payload:
			default:
			}
		}
	case frameClose:
		n.mu.Lock()
		t := n.tunnels[tid]
		delete(n.tunnels, tid)
		n.mu.Unlock()
		if t != nil {
			t.close()
		}
	}
}

func (n *DNode) runTunnel(tid uint32, host string, port uint16) {
	addr := net.JoinHostPort(host, fmt.Sprintf("%d", port))
	conn, err := net.DialTimeout("tcp", addr, 10*time.Second)
	if err != nil {
		n.sendFrame(tid, frameConnectFail, []byte(err.Error()))
		appLog("dnode节点: tid=%08x 连接失败 %s: %v", tid, addr, err)
		return
	}
	n.sendFrame(tid, frameConnectOK, nil)
	t := &dnodeTunnel{id: tid, conn: conn, ch: make(chan []byte, 64)}
	n.mu.Lock()
	n.tunnels[tid] = t
	n.mu.Unlock()
	appLog("dnode节点: tid=%08x 隧道已建 %s", tid, addr)

	// tcp -> ws
	go func() {
		buf := make([]byte, 65536)
		for {
			rn, err := conn.Read(buf)
			if rn > 0 {
				n.sendFrame(tid, frameData, buf[:rn])
			}
			if err != nil {
				break
			}
		}
		n.sendFrame(tid, frameClose, nil)
		n.mu.Lock()
		delete(n.tunnels, tid)
		n.mu.Unlock()
		t.close()
	}()

	// ws -> tcp
	go func() {
		for chunk := range t.ch {
			if _, err := conn.Write(chunk); err != nil {
				break
			}
		}
		_ = conn.Close()
	}()
}
