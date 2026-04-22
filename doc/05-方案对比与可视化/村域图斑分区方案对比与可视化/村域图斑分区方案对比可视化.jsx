import { useState, useCallback, useMemo, useEffect } from "react";

// ============================================================
// 数据生成与算法核心
// ============================================================

function generateParcels(rows, cols, width, height, seed = 42) {
  const rng = mulberry32(seed);
  const cellW = width / cols;
  const cellH = height / rows;
  const parcels = [];
  
  // 生成带扰动的网格点
  const gridX = Array.from({length: rows+1}, () => Array(cols+1).fill(0));
  const gridY = Array.from({length: rows+1}, () => Array(cols+1).fill(0));
  
  for (let i = 0; i <= rows; i++) {
    for (let j = 0; j <= cols; j++) {
      gridX[i][j] = j * cellW;
      gridY[i][j] = i * cellH;
      if (i > 0 && i < rows && j > 0 && j < cols) {
        gridX[i][j] += (rng() - 0.5) * cellW * 0.3;
        gridY[i][j] += (rng() - 0.5) * cellH * 0.3;
      }
    }
  }
  
  for (let i = 0; i < rows; i++) {
    for (let j = 0; j < cols; j++) {
      const poly = [
        [gridX[i][j], gridY[i][j]],
        [gridX[i][j+1], gridY[i][j+1]],
        [gridX[i+1][j+1], gridY[i+1][j+1]],
        [gridX[i+1][j], gridY[i+1][j]],
      ];
      const cx = poly.reduce((s, p) => s + p[0], 0) / 4;
      const cy = poly.reduce((s, p) => s + p[1], 0) / 4;
      parcels.push({ id: i * cols + j, poly, cx, cy, row: i, col: j });
    }
  }
  return parcels;
}

function mulberry32(a) {
  return function() {
    a |= 0; a = a + 0x6D2B79F5 | 0;
    var t = Math.imul(a ^ a >>> 15, 1 | a);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  }
}

// 坐标递归二分算法
function coordinateBisection(parcels, threshold, bbox) {
  const steps = [];
  const regions = [];
  let regionId = 0;
  
  function recurse(parcelSet, bounds, depth) {
    if (parcelSet.length <= threshold) {
      const rid = regionId++;
      regions.push({ id: rid, parcels: parcelSet, bounds });
      steps.push({
        type: 'leaf',
        desc: `区域 #${rid} 包含 ${parcelSet.length} 个图斑 ≤ 阈值 ${threshold}，停止切分`,
        parcels: parcelSet,
        regionId: rid,
        regions: JSON.parse(JSON.stringify(regions)),
        depth,
      });
      return;
    }
    
    // 选轴
    const w = bounds.maxX - bounds.minX;
    const h = bounds.maxY - bounds.minY;
    const axis = w >= h ? 'x' : 'y';
    
    // 排序
    const sorted = [...parcelSet].sort((a, b) => 
      axis === 'x' ? a.cx - b.cx : a.cy - b.cy
    );
    
    const mid = Math.floor(sorted.length / 2);
    const splitVal = axis === 'x' 
      ? (sorted[mid-1].cx + sorted[mid].cx) / 2
      : (sorted[mid-1].cy + sorted[mid].cy) / 2;
    
    // 检测是否有图斑被切割线穿过
    const conflicts = sorted.filter(p => {
      if (axis === 'x') {
        const minX = Math.min(...p.poly.map(pt => pt[0]));
        const maxX = Math.max(...p.poly.map(pt => pt[0]));
        return minX < splitVal && maxX > splitVal;
      } else {
        const minY = Math.min(...p.poly.map(pt => pt[1]));
        const maxY = Math.max(...p.poly.map(pt => pt[1]));
        return minY < splitVal && maxY > splitVal;
      }
    });
    
    steps.push({
      type: 'split',
      desc: `深度${depth}: 沿${axis === 'x' ? 'X轴(垂直线)' : 'Y轴(水平线)'}切分 ${sorted.length} 个图斑` +
            (conflicts.length > 0 ? `，发现 ${conflicts.length} 个冲突图斑` : '，无冲突'),
      axis,
      splitVal,
      parcels: sorted,
      conflicts,
      bounds,
      depth,
      regions: JSON.parse(JSON.stringify(regions)),
    });
    
    // 按质心分配（冲突图斑也按质心分配，不会被切开）
    const left = sorted.filter(p => (axis === 'x' ? p.cx : p.cy) < splitVal);
    const right = sorted.filter(p => (axis === 'x' ? p.cx : p.cy) >= splitVal);
    
    if (conflicts.length > 0) {
      // 调整切分值，避开冲突
      let adjusted = splitVal;
      // 找到安全间隙
      const projections = sorted.map(p => {
        if (axis === 'x') {
          return { min: Math.min(...p.poly.map(pt => pt[0])), max: Math.max(...p.poly.map(pt => pt[0])), p };
        } else {
          return { min: Math.min(...p.poly.map(pt => pt[1])), max: Math.max(...p.poly.map(pt => pt[1])), p };
        }
      }).sort((a, b) => a.min - b.min);
      
      // 合并投影区间找间隙
      let merged = [];
      let cur = { ...projections[0] };
      for (let k = 1; k < projections.length; k++) {
        if (projections[k].min <= cur.max + 0.01) {
          cur.max = Math.max(cur.max, projections[k].max);
        } else {
          merged.push(cur);
          cur = { ...projections[k] };
        }
      }
      merged.push(cur);
      
      // 找最近的间隙中心
      let bestGap = null, bestDist = Infinity;
      for (let k = 0; k < merged.length - 1; k++) {
        const gapCenter = (merged[k].max + merged[k+1].min) / 2;
        const dist = Math.abs(gapCenter - splitVal);
        if (dist < bestDist) { bestDist = dist; bestGap = gapCenter; }
      }
      
      if (bestGap !== null) {
        adjusted = bestGap;
        steps.push({
          type: 'adjust',
          desc: `调整切割线：从 ${splitVal.toFixed(1)} 移动到 ${adjusted.toFixed(1)}（安全间隙位置）`,
          axis, splitVal: adjusted, oldSplitVal: splitVal,
          parcels: sorted, conflicts, bounds, depth,
          regions: JSON.parse(JSON.stringify(regions)),
        });
      }
    }
    
    const boundsL = axis === 'x'
      ? { ...bounds, maxX: splitVal } : { ...bounds, maxY: splitVal };
    const boundsR = axis === 'x'
      ? { ...bounds, minX: splitVal } : { ...bounds, minY: splitVal };
    
    recurse(left, boundsL, depth + 1);
    recurse(right, boundsR, depth + 1);
  }
  
  recurse(parcels, bbox, 0);
  return { steps, regions };
}

// 构建邻接图
function buildAdjacency(parcels) {
  const edges = [];
  for (let i = 0; i < parcels.length; i++) {
    for (let j = i + 1; j < parcels.length; j++) {
      // 检查共边：共享两个点（简化判断）
      const pi = parcels[i], pj = parcels[j];
      let shared = 0;
      for (const a of pi.poly) {
        for (const b of pj.poly) {
          if (Math.abs(a[0]-b[0]) < 0.5 && Math.abs(a[1]-b[1]) < 0.5) shared++;
        }
      }
      if (shared >= 2) {
        edges.push([i, j]);
      }
    }
  }
  
  const adj = parcels.map(() => []);
  for (const [u, v] of edges) {
    adj[u].push(v);
    adj[v].push(u);
  }
  return { edges, adj };
}

// 图划分算法（BFS区域生长 + KL细化）
function graphPartition(parcels, threshold, adj, edges) {
  const steps = [];
  const n = parcels.length;
  const numGroups = Math.ceil(n / threshold);
  
  // 选种子点：均匀分布的质心
  const seeds = [];
  const sorted = [...parcels].sort((a, b) => a.cx - b.cx);
  for (let g = 0; g < numGroups; g++) {
    const idx = Math.floor((g + 0.5) * n / numGroups);
    seeds.push(sorted[idx].id);
  }
  
  steps.push({
    type: 'seeds',
    desc: `图论方法：选择 ${numGroups} 个种子节点，从它们开始BFS生长`,
    seeds,
    parcels,
  });
  
  // BFS 区域生长
  const assignment = new Array(n).fill(-1);
  const queues = seeds.map((s, i) => { assignment[s] = i; return [s]; });
  let round = 0;
  
  while (queues.some(q => q.length > 0)) {
    round++;
    const newQueues = queues.map(() => []);
    const groupSizes = Array(numGroups).fill(0);
    for (let i = 0; i < n; i++) {
      if (assignment[i] >= 0) groupSizes[assignment[i]]++;
    }
    
    for (let g = 0; g < numGroups; g++) {
      const q = queues[g];
      const nextQ = [];
      for (const node of q) {
        for (const neighbor of adj[node]) {
          if (assignment[neighbor] === -1 && groupSizes[g] < threshold) {
            assignment[neighbor] = g;
            groupSizes[g]++;
            nextQ.push(neighbor);
          }
        }
      }
      newQueues[g] = nextQ;
    }
    
    for (let g = 0; g < numGroups; g++) queues[g] = newQueues[g];
    
    if (round <= 8 || queues.every(q => q.length === 0)) {
      steps.push({
        type: 'grow',
        desc: `BFS第${round}轮：各区域同时向外扩展一层`,
        assignment: [...assignment],
        parcels,
        round,
      });
    }
  }
  
  // 分配剩余未分配的节点
  for (let i = 0; i < n; i++) {
    if (assignment[i] === -1) {
      // 分配到最近的已分配邻居的组
      let bestGroup = 0, bestDist = Infinity;
      for (let j = 0; j < n; j++) {
        if (assignment[j] >= 0) {
          const d = Math.hypot(parcels[i].cx - parcels[j].cx, parcels[i].cy - parcels[j].cy);
          if (d < bestDist) { bestDist = d; bestGroup = assignment[j]; }
        }
      }
      assignment[i] = bestGroup;
    }
  }
  
  // KL细化
  let cutEdgesBefore = countCutEdges(edges, assignment);
  steps.push({
    type: 'pre-kl',
    desc: `BFS完成，切边数=${cutEdgesBefore}。开始KL细化...`,
    assignment: [...assignment],
    parcels,
    cutEdges: cutEdgesBefore,
  });
  
  for (let iter = 0; iter < 5; iter++) {
    let improved = false;
    for (const [u, v] of edges) {
      if (assignment[u] === assignment[v]) continue;
      // 尝试交换
      const gu = assignment[u], gv = assignment[v];
      const sizeU = assignment.filter(a => a === gu).length;
      const sizeV = assignment.filter(a => a === gv).length;
      
      // 计算交换增益
      let gainU = 0, gainV = 0;
      for (const nb of adj[u]) {
        if (assignment[nb] === gv) gainU++;
        else if (assignment[nb] === gu) gainU--;
      }
      for (const nb of adj[v]) {
        if (assignment[nb] === gu) gainV++;
        else if (assignment[nb] === gv) gainV--;
      }
      
      if (gainU + gainV > 2 && sizeU > 1 && sizeV > 1) {
        assignment[u] = gv;
        assignment[v] = gu;
        improved = true;
      }
    }
    if (!improved) break;
  }
  
  let cutEdgesAfter = countCutEdges(edges, assignment);
  
  steps.push({
    type: 'post-kl',
    desc: `KL细化完成：切边数 ${cutEdgesBefore} → ${cutEdgesAfter}`,
    assignment: [...assignment],
    parcels,
    cutEdges: cutEdgesAfter,
  });
  
  // 构建最终结果
  const regions = [];
  for (let g = 0; g < numGroups; g++) {
    const gParcels = parcels.filter(p => assignment[p.id] === g);
    regions.push({ id: g, parcels: gParcels });
  }
  
  return { steps, regions, assignment, cutEdges: cutEdgesAfter };
}

function countCutEdges(edges, assignment) {
  return edges.filter(([u, v]) => assignment[u] !== assignment[v]).length;
}

// ============================================================
// 颜色方案
// ============================================================
const REGION_COLORS = [
  '#3B82F6', '#EF4444', '#10B981', '#F59E0B', '#8B5CF6',
  '#EC4899', '#06B6D4', '#F97316', '#6366F1', '#14B8A6',
  '#E11D48', '#84CC16', '#0EA5E9', '#D946EF', '#FB923C',
];
const BG_COLOR = '#0F172A';
const GRID_COLOR = '#1E293B';
const TEXT_COLOR = '#E2E8F0';
const DIM_TEXT = '#94A3B8';
const ACCENT = '#38BDF8';
const WARN = '#FBBF24';
const DANGER = '#EF4444';

// ============================================================
// SVG绘图组件
// ============================================================

function ParcelSVG({ parcels, width, height, scale, offsetX, offsetY, 
                     assignment, highlighted, conflicts, cutLine, cutLineOld,
                     edges, cutEdgeAssignment, seeds }) {
  
  const toScreen = (x, y) => [x * scale + offsetX, y * scale + offsetY];
  
  return (
    <svg width={width} height={height} style={{background: BG_COLOR, borderRadius: 8}}>
      {/* 网格 */}
      {Array.from({length: 11}, (_, i) => (
        <line key={`gx${i}`} x1={i * width/10} y1={0} x2={i * width/10} y2={height}
              stroke={GRID_COLOR} strokeWidth={0.5}/>
      ))}
      {Array.from({length: 11}, (_, i) => (
        <line key={`gy${i}`} x1={0} y1={i * height/10} x2={width} y2={i * height/10}
              stroke={GRID_COLOR} strokeWidth={0.5}/>
      ))}
      
      {/* 邻接边 */}
      {edges && edges.map(([u, v], i) => {
        const pu = parcels[u], pv = parcels[v];
        const [x1, y1] = toScreen(pu.cx, pu.cy);
        const [x2, y2] = toScreen(pv.cx, pv.cy);
        const isCut = cutEdgeAssignment && cutEdgeAssignment[u] !== cutEdgeAssignment[v];
        return (
          <line key={`e${i}`} x1={x1} y1={y1} x2={x2} y2={y2}
                stroke={isCut ? DANGER : '#334155'} strokeWidth={isCut ? 2 : 0.8}
                opacity={isCut ? 0.8 : 0.4} strokeDasharray={isCut ? "4,3" : "none"}/>
        );
      })}
      
      {/* 图斑多边形 */}
      {parcels.map((p, i) => {
        const pts = p.poly.map(([x, y]) => toScreen(x, y).join(',')).join(' ');
        const isConflict = conflicts && conflicts.some(c => c.id === p.id);
        const isHighlighted = highlighted && highlighted.includes(p.id);
        const groupId = assignment ? assignment[p.id] : null;
        
        let fill = '#1E293B';
        let stroke = '#475569';
        let strokeW = 1;
        let opacity = 0.85;
        
        if (groupId !== null && groupId >= 0) {
          fill = REGION_COLORS[groupId % REGION_COLORS.length];
          opacity = 0.55;
          stroke = REGION_COLORS[groupId % REGION_COLORS.length];
          strokeW = 1.5;
        }
        if (isConflict) {
          fill = DANGER;
          opacity = 0.7;
          stroke = '#FCA5A5';
          strokeW = 2.5;
        }
        if (isHighlighted) {
          stroke = ACCENT;
          strokeW = 2;
        }
        
        return (
          <g key={`p${i}`}>
            <polygon points={pts} fill={fill} fillOpacity={opacity}
                     stroke={stroke} strokeWidth={strokeW}/>
            {seeds && seeds.includes(p.id) && (
              <circle cx={toScreen(p.cx, p.cy)[0]} cy={toScreen(p.cx, p.cy)[1]}
                      r={6} fill="#FFF" stroke={REGION_COLORS[assignment ? assignment[p.id] : 0]}
                      strokeWidth={2}/>
            )}
          </g>
        );
      })}
      
      {/* 切割线 */}
      {cutLineOld && (
        <line x1={cutLineOld.x1} y1={cutLineOld.y1} x2={cutLineOld.x2} y2={cutLineOld.y2}
              stroke={WARN} strokeWidth={1.5} strokeDasharray="6,4" opacity={0.5}/>
      )}
      {cutLine && (
        <line x1={cutLine.x1} y1={cutLine.y1} x2={cutLine.x2} y2={cutLine.y2}
              stroke={cutLine.safe ? '#10B981' : DANGER} strokeWidth={2.5}
              strokeDasharray={cutLine.safe ? "none" : "8,4"}/>
      )}
      
      {/* 切割线标注 */}
      {cutLine && (
        <text x={cutLine.labelX} y={cutLine.labelY} fill={cutLine.safe ? '#10B981' : DANGER}
              fontSize={11} fontWeight={600} fontFamily="monospace">
          {cutLine.label}
        </text>
      )}
    </svg>
  );
}

// ============================================================
// 主组件
// ============================================================

export default function VillageSplitVisualizer() {
  const [mode, setMode] = useState('coord'); // 'coord' | 'graph'
  const [step, setStep] = useState(0);
  const [threshold, setThreshold] = useState(9);
  const [gridSize, setGridSize] = useState(6);
  
  const SVG_W = 520, SVG_H = 420;
  const DATA_W = 600, DATA_H = 480;
  const MARGIN = 40;
  const scale = Math.min((SVG_W - MARGIN*2) / DATA_W, (SVG_H - MARGIN*2) / DATA_H);
  const offsetX = MARGIN;
  const offsetY = MARGIN;
  
  const parcels = useMemo(() => generateParcels(gridSize, gridSize, DATA_W, DATA_H, 42), [gridSize]);
  const { edges, adj } = useMemo(() => buildAdjacency(parcels), [parcels]);
  
  const bbox = { minX: 0, minY: 0, maxX: DATA_W, maxY: DATA_H };
  
  const coordResult = useMemo(() => coordinateBisection(parcels, threshold, bbox), [parcels, threshold]);
  const graphResult = useMemo(() => graphPartition(parcels, threshold, adj, edges), [parcels, threshold, adj, edges]);
  
  const currentResult = mode === 'coord' ? coordResult : graphResult;
  const currentStep = currentResult.steps[Math.min(step, currentResult.steps.length - 1)];
  const maxStep = currentResult.steps.length - 1;
  
  useEffect(() => { setStep(0); }, [mode, threshold, gridSize]);
  
  // 根据当前步骤构建SVG参数
  const svgProps = useMemo(() => {
    const s = currentStep;
    if (!s) return { parcels, assignment: null, conflicts: null, cutLine: null };
    
    const toScreen = (x, y) => [x * scale + offsetX, y * scale + offsetY];
    
    if (mode === 'coord') {
      // 构建到当前步骤为止的所有已分配区域
      let assignment = {};
      const allRegions = s.regions || [];
      for (const r of allRegions) {
        for (const p of r.parcels) assignment[p.id] = r.id;
      }
      
      let cutLine = null, cutLineOld = null;
      if (s.type === 'split' || s.type === 'adjust') {
        const b = s.bounds;
        if (s.axis === 'x') {
          const [x1, y1] = toScreen(s.splitVal, b.minY);
          const [x2, y2] = toScreen(s.splitVal, b.maxY);
          cutLine = { x1, y1, x2, y2, safe: s.conflicts.length === 0,
                     labelX: x1 + 5, labelY: y1 + 15,
                     label: `x=${s.splitVal.toFixed(0)}` };
        } else {
          const [x1, y1] = toScreen(b.minX, s.splitVal);
          const [x2, y2] = toScreen(b.maxX, s.splitVal);
          cutLine = { x1, y1, x2, y2, safe: s.conflicts.length === 0,
                     labelX: x2 - 60, labelY: y1 - 5,
                     label: `y=${s.splitVal.toFixed(0)}` };
        }
        if (s.type === 'adjust' && s.oldSplitVal) {
          if (s.axis === 'x') {
            const [ox1, oy1] = toScreen(s.oldSplitVal, s.bounds.minY);
            const [ox2, oy2] = toScreen(s.oldSplitVal, s.bounds.maxY);
            cutLineOld = { x1: ox1, y1: oy1, x2: ox2, y2: oy2 };
          } else {
            const [ox1, oy1] = toScreen(s.bounds.minX, s.oldSplitVal);
            const [ox2, oy2] = toScreen(s.bounds.maxX, s.oldSplitVal);
            cutLineOld = { x1: ox1, y1: oy1, x2: ox2, y2: oy2 };
          }
        }
      }
      if (s.type === 'leaf') {
        for (const p of s.parcels) assignment[p.id] = s.regionId;
      }
      
      return { 
        parcels, assignment, 
        conflicts: s.conflicts || [], 
        cutLine, cutLineOld,
        highlighted: s.parcels ? s.parcels.map(p => p.id) : null 
      };
    } else {
      // 图论模式
      let assignment = {};
      if (s.assignment) {
        for (let i = 0; i < s.assignment.length; i++) {
          if (s.assignment[i] >= 0) assignment[i] = s.assignment[i];
        }
      }
      return {
        parcels, 
        assignment: Object.keys(assignment).length > 0 ? assignment : null,
        conflicts: null, cutLine: null, cutLineOld: null,
        showEdges: true,
        cutEdgeAssignment: s.assignment || null,
        seeds: s.seeds || null,
      };
    }
  }, [currentStep, mode, parcels, scale, offsetX, offsetY]);
  
  // 统计信息
  const stats = useMemo(() => {
    const regions = currentResult.regions;
    if (!regions || regions.length === 0) return null;
    const counts = regions.map(r => r.parcels.length);
    const max = Math.max(...counts);
    const min = Math.min(...counts);
    const avg = (counts.reduce((a,b) => a+b, 0) / counts.length).toFixed(1);
    const balance = (max / parseFloat(avg)).toFixed(2);
    return { count: regions.length, max, min, avg, balance };
  }, [currentResult]);

  return (
    <div style={{
      background: '#020617', color: TEXT_COLOR, minHeight: '100vh',
      fontFamily: "'JetBrains Mono', 'SF Mono', 'Fira Code', monospace",
      padding: '24px',
    }}>
      {/* 标题 */}
      <div style={{ textAlign: 'center', marginBottom: 20 }}>
        <h1 style={{ 
          fontSize: 22, fontWeight: 700, color: '#F8FAFC', margin: 0,
          letterSpacing: '0.05em'
        }}>
          村边界空间分区算法 <span style={{color: ACCENT}}>可视化</span>
        </h1>
        <p style={{ fontSize: 12, color: DIM_TEXT, marginTop: 4 }}>
          坐标递归二分 vs 图论划分 · 逐步演示
        </p>
      </div>

      {/* 控制面板 */}
      <div style={{
        display: 'flex', gap: 16, justifyContent: 'center', flexWrap: 'wrap',
        marginBottom: 16, alignItems: 'center',
      }}>
        {/* 算法选择 */}
        <div style={{ display: 'flex', background: '#1E293B', borderRadius: 8, overflow: 'hidden' }}>
          {[['coord', '坐标递归二分'], ['graph', '图论划分']].map(([key, label]) => (
            <button key={key} onClick={() => setMode(key)}
              style={{
                padding: '8px 16px', border: 'none', cursor: 'pointer',
                background: mode === key ? ACCENT : 'transparent',
                color: mode === key ? '#0F172A' : DIM_TEXT,
                fontWeight: mode === key ? 700 : 400,
                fontSize: 13, fontFamily: 'inherit', transition: 'all 0.2s',
              }}>
              {label}
            </button>
          ))}
        </div>
        
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 12, color: DIM_TEXT }}>网格</span>
          <select value={gridSize} onChange={e => setGridSize(+e.target.value)}
            style={{ background: '#1E293B', color: TEXT_COLOR, border: '1px solid #334155',
                     borderRadius: 6, padding: '6px 10px', fontSize: 12, fontFamily: 'inherit' }}>
            {[4,5,6,7,8].map(s => <option key={s} value={s}>{s}×{s} ({s*s})</option>)}
          </select>
        </div>
        
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 12, color: DIM_TEXT }}>阈值</span>
          <select value={threshold} onChange={e => setThreshold(+e.target.value)}
            style={{ background: '#1E293B', color: TEXT_COLOR, border: '1px solid #334155',
                     borderRadius: 6, padding: '6px 10px', fontSize: 12, fontFamily: 'inherit' }}>
            {[4,6,9,12,16,20].map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
      </div>

      {/* 主面板 */}
      <div style={{ display: 'flex', gap: 16, justifyContent: 'center', flexWrap: 'wrap' }}>
        {/* 左：SVG */}
        <div>
          <ParcelSVG 
            parcels={parcels} width={SVG_W} height={SVG_H}
            scale={scale} offsetX={offsetX} offsetY={offsetY}
            assignment={svgProps.assignment}
            highlighted={svgProps.highlighted}
            conflicts={svgProps.conflicts}
            cutLine={svgProps.cutLine}
            cutLineOld={svgProps.cutLineOld}
            edges={mode === 'graph' ? edges : null}
            cutEdgeAssignment={svgProps.cutEdgeAssignment}
            seeds={svgProps.seeds}
          />
          
          {/* 步骤控制 */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: 8, marginTop: 10,
            justifyContent: 'center',
          }}>
            <button onClick={() => setStep(0)} disabled={step === 0}
              style={btnStyle(step === 0)}>⏮</button>
            <button onClick={() => setStep(Math.max(0, step - 1))} disabled={step === 0}
              style={btnStyle(step === 0)}>◀</button>
            <span style={{ 
              fontSize: 13, color: ACCENT, minWidth: 80, textAlign: 'center',
              fontWeight: 600,
            }}>
              {step + 1} / {maxStep + 1}
            </span>
            <button onClick={() => setStep(Math.min(maxStep, step + 1))} disabled={step >= maxStep}
              style={btnStyle(step >= maxStep)}>▶</button>
            <button onClick={() => setStep(maxStep)} disabled={step >= maxStep}
              style={btnStyle(step >= maxStep)}>⏭</button>
          </div>
        </div>

        {/* 右：说明面板 */}
        <div style={{ width: 340 }}>
          {/* 当前步骤说明 */}
          <div style={{
            background: '#1E293B', borderRadius: 8, padding: 16,
            borderLeft: `3px solid ${ACCENT}`, marginBottom: 12,
          }}>
            <div style={{ fontSize: 11, color: DIM_TEXT, marginBottom: 6, textTransform: 'uppercase', letterSpacing: '0.1em' }}>
              当前步骤
            </div>
            <div style={{ fontSize: 13, lineHeight: 1.6, color: '#F1F5F9' }}>
              {currentStep?.desc || '准备中...'}
            </div>
            
            {/* 图例 */}
            {mode === 'coord' && currentStep?.type === 'split' && (
              <div style={{ marginTop: 12, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
                <Legend color="#10B981" label="安全切割线" />
                <Legend color={DANGER} label="冲突切割线" />
                <Legend color={DANGER} label="冲突图斑" filled />
                <Legend color={WARN} label="调整前位置" dashed />
              </div>
            )}
            {mode === 'graph' && (
              <div style={{ marginTop: 12, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
                <Legend color={DANGER} label="切边" dashed />
                <Legend color="#334155" label="内部边" />
                <Legend color="#FFF" label="种子节点" circle />
              </div>
            )}
          </div>

          {/* 算法解释 */}
          <div style={{
            background: '#1E293B', borderRadius: 8, padding: 16, marginBottom: 12,
          }}>
            <div style={{ fontSize: 11, color: DIM_TEXT, marginBottom: 6, textTransform: 'uppercase', letterSpacing: '0.1em' }}>
              {mode === 'coord' ? '坐标递归二分法' : '图论划分法'} · 核心思想
            </div>
            {mode === 'coord' ? (
              <div style={{ fontSize: 12, lineHeight: 1.7, color: '#CBD5E1' }}>
                <p style={{margin: '0 0 8px'}}>① 选择包围盒<b style={{color: ACCENT}}>较长轴</b>方向</p>
                <p style={{margin: '0 0 8px'}}>② 按质心坐标排序，在<b style={{color: ACCENT}}>中位数</b>处切分</p>
                <p style={{margin: '0 0 8px'}}>③ 检测切割线是否<b style={{color: DANGER}}>穿过图斑</b></p>
                <p style={{margin: '0 0 8px'}}>④ 若有冲突 → 在投影区间的<b style={{color: WARN}}>安全间隙</b>中寻找新位置</p>
                <p style={{margin: '0 0 8px'}}>⑤ 用调整后的线切分区域，<b style={{color: '#10B981'}}>递归</b>处理子区域</p>
              </div>
            ) : (
              <div style={{ fontSize: 12, lineHeight: 1.7, color: '#CBD5E1' }}>
                <p style={{margin: '0 0 8px'}}>① 构建图斑<b style={{color: ACCENT}}>邻接图</b>（共边=连边）</p>
                <p style={{margin: '0 0 8px'}}>② 选择均匀分布的<b style={{color: '#FFF'}}>种子节点</b></p>
                <p style={{margin: '0 0 8px'}}>③ 多源<b style={{color: ACCENT}}>BFS同时生长</b>，先到先得</p>
                <p style={{margin: '0 0 8px'}}>④ <b style={{color: WARN}}>KL细化</b>：交换边界节点减少切边</p>
                <p style={{margin: '0 0 8px'}}>⑤ 切边数越少 → 切割线越短 → 区域越<b style={{color: '#10B981'}}>紧凑</b></p>
              </div>
            )}
          </div>

          {/* 最终统计 */}
          {stats && step >= maxStep && (
            <div style={{
              background: 'linear-gradient(135deg, #1E293B, #0F172A)',
              borderRadius: 8, padding: 16, border: `1px solid ${ACCENT}33`,
            }}>
              <div style={{ fontSize: 11, color: ACCENT, marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.1em' }}>
                最终结果
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <StatBox label="子区域数" value={stats.count} />
                <StatBox label="均衡度" value={stats.balance} note="1.0=完美" />
                <StatBox label="最大" value={stats.max} />
                <StatBox label="最小" value={stats.min} />
                <StatBox label="平均" value={stats.avg} />
                <StatBox label="阈值" value={threshold} />
              </div>
              {mode === 'graph' && graphResult.cutEdges !== undefined && (
                <div style={{ marginTop: 8, fontSize: 12, color: DIM_TEXT }}>
                  切边数: <span style={{ color: DANGER, fontWeight: 600 }}>{graphResult.cutEdges}</span>
                  / {edges.length} 总边
                </div>
              )}
            </div>
          )}
        </div>
      </div>
      
      {/* 对比表格 */}
      <div style={{
        maxWidth: 880, margin: '24px auto 0', background: '#1E293B',
        borderRadius: 8, padding: 20, overflow: 'auto',
      }}>
        <div style={{ fontSize: 14, fontWeight: 700, color: '#F8FAFC', marginBottom: 12 }}>
          方案对比分析
        </div>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr style={{ borderBottom: `2px solid ${ACCENT}33` }}>
              {['维度', '坐标递归二分', '图论划分'].map(h => (
                <th key={h} style={{ padding: '8px 12px', textAlign: 'left', color: ACCENT, fontWeight: 600 }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {[
              ['切割方式', '直线切割（沿坐标轴方向）', '沿图斑共边切割（图的切边）'],
              ['拓扑安全', '需检测+调整切割线位置', '天然保证（从不穿过图斑）'],
              ['均衡性', '较好（中位数保证二分均衡）', '取决于BFS生长和KL细化'],
              ['区域形状', '矩形倾向，较规整', '不规则但紧凑，贴合图斑分布'],
              ['几何输出', '直接（切割线就是子区域边界）', '需后处理（从图分组恢复几何）'],
              ['时间复杂度', 'O(N log N)', 'O(N log N) 建图 + O(N) 划分'],
              ['实现难度', '★★☆☆☆', '★★★★☆'],
              ['适合场景', '图斑分布均匀，对形状要求不高', '需要最小化跨区域关系，形状自然'],
            ].map(([dim, a, b], i) => (
              <tr key={i} style={{ borderBottom: '1px solid #334155' }}>
                <td style={{ padding: '8px 12px', color: WARN, fontWeight: 600 }}>{dim}</td>
                <td style={{ padding: '8px 12px', color: '#CBD5E1' }}>{a}</td>
                <td style={{ padding: '8px 12px', color: '#CBD5E1' }}>{b}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      
      {/* 关键洞察 */}
      <div style={{
        maxWidth: 880, margin: '16px auto 0',
        display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12,
      }}>
        <InsightCard 
          title="为什么需要拓扑安全？"
          content="如果切割线穿过图斑，会导致同一地块被分到两个子区域，在后续操作（面积统计、权属管理）中产生数据错误。这是硬约束，不是优化目标。"
          icon="🔒"
        />
        <InsightCard 
          title="为什么间隙搜索有效？"
          content="真实地块之间总有道路、水渠等间隙。将图斑投影到切割轴上，合并重叠区间后，间隙对应的就是安全切割位置。这是一维情形的直接推广。"
          icon="🎯"
        />
        <InsightCard 
          title="图论方法的核心优势？"
          content="切边数直接对应被切割线分开的相邻地块数。KL细化通过交换边界节点来最小化切边——这是图划分理论70年的核心结果（Kernighan-Lin 1970）。"
          icon="📊"
        />
        <InsightCard 
          title="实际项目如何选择？"
          content="如果只需要快速切分、对区域形状要求不高 → 坐标递归二分。如果需要最小化跨区域关系、区域形状贴合地形 → 图论划分。两者可以组合使用。"
          icon="⚖️"
        />
      </div>
    </div>
  );
}

function Legend({ color, label, filled, dashed, circle }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11 }}>
      {circle ? (
        <div style={{ width: 10, height: 10, borderRadius: '50%', background: '#FFF',
                       border: `2px solid ${color}` }}/>
      ) : (
        <div style={{ 
          width: 16, height: filled ? 10 : 3, 
          background: filled ? color : 'transparent',
          borderTop: !filled ? `2px ${dashed ? 'dashed' : 'solid'} ${color}` : 'none',
          borderRadius: filled ? 2 : 0,
        }}/>
      )}
      <span style={{ color: DIM_TEXT }}>{label}</span>
    </div>
  );
}

function StatBox({ label, value, note }) {
  return (
    <div style={{ background: '#0F172A', borderRadius: 6, padding: '8px 10px' }}>
      <div style={{ fontSize: 10, color: DIM_TEXT }}>{label}</div>
      <div style={{ fontSize: 18, fontWeight: 700, color: '#F8FAFC' }}>
        {value}
        {note && <span style={{ fontSize: 9, color: DIM_TEXT, marginLeft: 4 }}>{note}</span>}
      </div>
    </div>
  );
}

function InsightCard({ title, content, icon }) {
  return (
    <div style={{
      background: '#1E293B', borderRadius: 8, padding: 14,
      borderTop: `2px solid ${ACCENT}44`,
    }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: '#F8FAFC', marginBottom: 6 }}>
        {icon} {title}
      </div>
      <div style={{ fontSize: 11.5, lineHeight: 1.7, color: '#94A3B8' }}>
        {content}
      </div>
    </div>
  );
}

function btnStyle(disabled) {
  return {
    padding: '6px 14px', border: 'none', borderRadius: 6, cursor: disabled ? 'default' : 'pointer',
    background: disabled ? '#1E293B' : '#334155',
    color: disabled ? '#475569' : TEXT_COLOR,
    fontSize: 14, fontFamily: 'inherit', transition: 'all 0.15s',
  };
}
